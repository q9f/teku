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

package tech.pegasys.artemis.storage.server.rocksdb;

import static com.google.common.primitives.UnsignedLong.ONE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import tech.pegasys.artemis.datastructures.blocks.SignedBlockAndState;
import tech.pegasys.artemis.datastructures.state.Checkpoint;
import tech.pegasys.artemis.storage.Store.Transaction;
import tech.pegasys.artemis.storage.server.AbstractStorageBackedDatabaseTest;
import tech.pegasys.artemis.util.async.SafeFuture;

public abstract class AbstractRocksDbDatabaseTest extends AbstractStorageBackedDatabaseTest {

  @Test
  public void shouldThrowIfClosedDatabaseIsModified_setGenesis() throws Exception {
    database.close();
    assertThatThrownBy(() -> database.storeGenesis(store))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  public void shouldThrowIfClosedDatabaseIsModified_update() throws Exception {
    database.storeGenesis(store);
    database.close();

    final Checkpoint newValue = checkpoint3;
    // Sanity check
    assertThat(store.getFinalizedCheckpoint()).isNotEqualTo(checkpoint3);
    final Transaction transaction = store.startTransaction(storageUpdateChannel);
    transaction.setFinalizedCheckpoint(newValue);

    final SafeFuture<Void> result = transaction.commit();
    assertThatThrownBy(result::get).hasCauseInstanceOf(IllegalStateException.class);
  }

  @Test
  public void shouldThrowIfClosedDatabaseIsRead_createMemoryStore() throws Exception {
    database.storeGenesis(store);
    database.close();

    assertThatThrownBy(database::createMemoryStore).isInstanceOf(IllegalStateException.class);
  }

  @Test
  public void shouldThrowIfClosedDatabaseIsRead_getFinalizedRootAtSlot() throws Exception {
    database.storeGenesis(store);
    database.close();

    assertThatThrownBy(() -> database.getFinalizedRootAtSlot(ONE))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  public void shouldThrowIfClosedDatabaseIsRead_getLatestFinalizedRootAtSlot() throws Exception {
    database.storeGenesis(store);
    database.close();

    assertThatThrownBy(() -> database.getLatestFinalizedRootAtSlot(ONE))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  public void shouldThrowIfClosedDatabaseIsRead_getSignedBlock() throws Exception {
    database.storeGenesis(store);
    database.close();

    assertThatThrownBy(() -> database.getSignedBlock(genesisCheckpoint.getRoot()))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  public void shouldThrowIfClosedDatabaseIsRead_getHistoricalState() throws Exception {
    // Store genesis
    database.storeGenesis(store);
    // Add a new finalized block to supersede genesis
    final SignedBlockAndState newBlock = chainBuilder.getBlockAndStateAtSlot(1);
    final Checkpoint newCheckpoint = getCheckpointForBlock(newBlock.getBlock());
    final Transaction transaction = store.startTransaction(storageUpdateChannel);
    transaction.putBlock(newBlock.getRoot(), newBlock.getBlock());
    transaction.putBlockState(newBlock.getRoot(), newBlock.getState());
    transaction.setFinalizedCheckpoint(newCheckpoint);
    transaction.commit().reportExceptions();
    // Close db
    database.close();

    assertThatThrownBy(() -> database.getState(genesisCheckpoint.getRoot()))
        .isInstanceOf(IllegalStateException.class);
  }
}
