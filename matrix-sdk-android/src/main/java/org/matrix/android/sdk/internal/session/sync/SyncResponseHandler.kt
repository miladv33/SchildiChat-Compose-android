/*
 * Copyright 2020 The Matrix.org Foundation C.I.C.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.matrix.android.sdk.internal.session.sync

import com.zhuinden.monarchy.Monarchy
import org.matrix.android.sdk.api.session.pushrules.PushRuleService
import org.matrix.android.sdk.api.session.pushrules.RuleScope
import org.matrix.android.sdk.api.session.sync.InitialSyncStep
import org.matrix.android.sdk.api.session.sync.model.RoomsSyncResponse
import org.matrix.android.sdk.api.session.sync.model.SyncResponse
import org.matrix.android.sdk.internal.SessionManager
import org.matrix.android.sdk.internal.crypto.DefaultCryptoService
import org.matrix.android.sdk.internal.di.SessionDatabase
import org.matrix.android.sdk.internal.di.SessionId
import org.matrix.android.sdk.internal.session.SessionListeners
import org.matrix.android.sdk.internal.session.dispatchTo
import org.matrix.android.sdk.internal.session.pushrules.ProcessEventForPushTask
import org.matrix.android.sdk.internal.session.sync.handler.CryptoSyncHandler
import org.matrix.android.sdk.internal.session.sync.handler.PresenceSyncHandler
import org.matrix.android.sdk.internal.session.sync.handler.SyncResponsePostTreatmentAggregatorHandler
import org.matrix.android.sdk.internal.session.sync.handler.UserAccountDataSyncHandler
import org.matrix.android.sdk.internal.session.sync.handler.room.RoomSyncHandler
import org.matrix.android.sdk.internal.util.awaitTransaction
import timber.log.Timber
import javax.inject.Inject
import kotlin.system.measureTimeMillis

internal class SyncResponseHandler @Inject constructor(
        @SessionDatabase private val monarchy: Monarchy,
        @SessionId private val sessionId: String,
        private val sessionManager: SessionManager,
        private val sessionListeners: SessionListeners,
        private val roomSyncHandler: RoomSyncHandler,
        private val userAccountDataSyncHandler: UserAccountDataSyncHandler,
        private val cryptoSyncHandler: CryptoSyncHandler,
        private val aggregatorHandler: SyncResponsePostTreatmentAggregatorHandler,
        private val cryptoService: DefaultCryptoService,
        private val tokenStore: SyncTokenStore,
        private val processEventForPushTask: ProcessEventForPushTask,
        private val pushRuleService: PushRuleService,
        private val presenceSyncHandler: PresenceSyncHandler
) {

    suspend fun handleResponse(
            syncResponse: SyncResponse,
            fromToken: String?,
            reporter: ProgressReporter?
    ) {
        val isInitialSync = fromToken == null
        Timber.v("Start handling sync, is InitialSync: $isInitialSync")

        measureTimeMillis {
            if (!cryptoService.isStarted()) {
                Timber.v("Should start cryptoService")
                cryptoService.start()
            }
            cryptoService.onSyncWillProcess(isInitialSync)
        }.also {
            Timber.i("Finish handling start cryptoService in $it ms")
        }

        // Handle the to device events before the room ones
        // to ensure to decrypt them properly
        measureTimeMillis {
            Timber.v("Handle toDevice")
            reportSubtask(reporter, InitialSyncStep.ImportingAccountCrypto, 100, 0.1f) {
                if (syncResponse.toDevice != null) {
                    cryptoSyncHandler.handleToDevice(syncResponse.toDevice, reporter)
                }
            }
        }.also {
            Timber.i("Finish handling toDevice in $it ms")
        }
        val aggregator = SyncResponsePostTreatmentAggregator()

        // Prerequisite for thread events handling in RoomSyncHandler
// Disabled due to the new fallback
//        if (!lightweightSettingsStorage.areThreadMessagesEnabled()) {
//            threadsAwarenessHandler.fetchRootThreadEventsIfNeeded(syncResponse)
//        }

        // Start one big transaction
        monarchy.awaitTransaction { realm ->
            // IMPORTANT nothing should be suspend here as we are accessing the realm instance (thread local)
            measureTimeMillis {
                Timber.v("Handle rooms")
                reportSubtask(reporter, InitialSyncStep.ImportingAccountRoom, 1, 0.8f) {
                    if (syncResponse.rooms != null) {
                        roomSyncHandler.handle(realm, syncResponse.rooms, isInitialSync, aggregator, reporter)
                    }
                }
            }.also {
                Timber.i("Finish handling rooms in $it ms")
            }

            measureTimeMillis {
                reportSubtask(reporter, InitialSyncStep.ImportingAccountData, 1, 0.1f) {
                    Timber.v("Handle accountData")
                    userAccountDataSyncHandler.handle(realm, syncResponse.accountData)
                }
            }.also {
                Timber.i("Finish handling accountData in $it ms")
            }

            measureTimeMillis {
                Timber.v("Handle Presence")
                presenceSyncHandler.handle(realm, syncResponse.presence)
            }.also {
                Timber.i("Finish handling Presence in $it ms")
            }
            tokenStore.saveToken(realm, syncResponse.nextBatch)
        }

        // Everything else we need to do outside the transaction
        measureTimeMillis {
            aggregatorHandler.handle(aggregator)
        }.also {
            Timber.i("Aggregator management took $it ms")
        }

        measureTimeMillis {
            syncResponse.rooms?.let {
                checkPushRules(it, isInitialSync)
                userAccountDataSyncHandler.synchronizeWithServerIfNeeded(it.invite)
                dispatchInvitedRoom(it)
            }
        }.also {
            Timber.i("SyncResponse.rooms post treatment took $it ms")
        }

        measureTimeMillis {
            cryptoSyncHandler.onSyncCompleted(syncResponse)
        }.also {
            Timber.i("cryptoSyncHandler.onSyncCompleted took $it ms")
        }

        // post sync stuffs
        measureTimeMillis {
            Timber.v("Handle postSyncSpaceHierarchy")
        monarchy.writeAsync {
            roomSyncHandler.postSyncSpaceHierarchyHandle(it)
        }
        }.also {
            Timber.i("postSyncSpaceHierarchy took $it ms")
        }

        Timber.v("On sync completed")
    }

    private fun dispatchInvitedRoom(roomsSyncResponse: RoomsSyncResponse) {
        val session = sessionManager.getSessionComponent(sessionId)?.session()
        roomsSyncResponse.invite.keys.forEach { roomId ->
            session.dispatchTo(sessionListeners) { session, listener ->
                listener.onNewInvitedRoom(session, roomId)
            }
        }
    }

    private suspend fun checkPushRules(roomsSyncResponse: RoomsSyncResponse, isInitialSync: Boolean) {
        Timber.v("[PushRules] --> checkPushRules")
        if (isInitialSync) {
            Timber.v("[PushRules] <-- No push rule check on initial sync")
            return
        } // nothing on initial sync

        val rules = pushRuleService.getPushRules(RuleScope.GLOBAL).getAllRules()
        processEventForPushTask.execute(ProcessEventForPushTask.Params(roomsSyncResponse, rules))
        Timber.v("[PushRules] <-- Push task scheduled")
    }
}
