/*
 * Copyright 2020 ConsenSys AG.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */

package tech.pegasys.artemis.beaconrestapi.schema;

import static com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL;

import com.fasterxml.jackson.annotation.JsonInclude;
import tech.pegasys.artemis.sync.SyncStatus;
import tech.pegasys.artemis.sync.SyncingStatus;

@JsonInclude(NON_NULL)
public class SyncingResponse {
  public final boolean is_syncing;
  public final SyncStatus sync_status;

  public SyncingResponse(boolean syncing, SyncStatus sync_status) {
    this.is_syncing = syncing;
    this.sync_status = sync_status;
  }

  public SyncingResponse(SyncingStatus syncStatus) {
    this.is_syncing = syncStatus.isSyncing();
    this.sync_status = syncStatus.getSyncStatus();
  }
}
