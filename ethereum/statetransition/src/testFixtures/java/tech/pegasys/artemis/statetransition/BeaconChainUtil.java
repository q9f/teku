/*
 * Copyright 2019 ConsenSys AG.
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

package tech.pegasys.artemis.statetransition;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static tech.pegasys.artemis.util.config.Constants.MIN_ATTESTATION_INCLUSION_DELAY;

import com.google.common.primitives.UnsignedLong;
import java.util.List;
import java.util.Optional;
import org.apache.tuweni.bytes.Bytes32;
import tech.pegasys.artemis.bls.BLSKeyPair;
import tech.pegasys.artemis.core.AttestationGenerator;
import tech.pegasys.artemis.core.BlockProposalTestUtil;
import tech.pegasys.artemis.core.ForkChoiceUtil;
import tech.pegasys.artemis.core.StateTransition;
import tech.pegasys.artemis.core.results.BlockImportResult;
import tech.pegasys.artemis.core.signatures.MessageSignerService;
import tech.pegasys.artemis.core.signatures.TestMessageSignerService;
import tech.pegasys.artemis.datastructures.blocks.BeaconBlock;
import tech.pegasys.artemis.datastructures.blocks.SignedBeaconBlock;
import tech.pegasys.artemis.datastructures.blocks.SignedBlockAndState;
import tech.pegasys.artemis.datastructures.operations.Attestation;
import tech.pegasys.artemis.datastructures.state.BeaconState;
import tech.pegasys.artemis.datastructures.util.MockStartValidatorKeyPairFactory;
import tech.pegasys.artemis.protoarray.StubForkChoiceStrategy;
import tech.pegasys.artemis.ssz.SSZTypes.SSZList;
import tech.pegasys.artemis.statetransition.util.StartupUtil;
import tech.pegasys.artemis.storage.Store.Transaction;
import tech.pegasys.artemis.storage.client.RecentChainData;
import tech.pegasys.artemis.util.async.SafeFuture;
import tech.pegasys.artemis.util.config.Constants;

public class BeaconChainUtil {

  private final StateTransition stateTransition = new StateTransition();
  private final BlockProposalTestUtil blockCreator = new BlockProposalTestUtil();
  private final RecentChainData recentChainData;
  private final List<BLSKeyPair> validatorKeys;
  private final boolean signDeposits;

  private BeaconChainUtil(
      final List<BLSKeyPair> validatorKeys,
      final RecentChainData recentChainData,
      boolean signDeposits) {
    this.validatorKeys = validatorKeys;
    this.recentChainData = recentChainData;
    this.signDeposits = signDeposits;
  }

  public static BeaconChainUtil create(
      final int validatorCount, final RecentChainData storageClient) {
    final List<BLSKeyPair> validatorKeys =
        new MockStartValidatorKeyPairFactory().generateKeyPairs(0, validatorCount);
    return create(storageClient, validatorKeys);
  }

  public static BeaconChainUtil create(
      final RecentChainData storageClient, final List<BLSKeyPair> validatorKeys) {
    return create(storageClient, validatorKeys, true);
  }

  public static BeaconChainUtil create(
      final RecentChainData storageClient,
      final List<BLSKeyPair> validatorKeys,
      final boolean signDeposits) {
    return new BeaconChainUtil(validatorKeys, storageClient, signDeposits);
  }

  public static void initializeStorage(
      final RecentChainData recentChainData, final List<BLSKeyPair> validatorKeys) {
    initializeStorage(recentChainData, validatorKeys, true);
  }

  public static void initializeStorage(
      final RecentChainData recentChainData,
      final List<BLSKeyPair> validatorKeys,
      final boolean signDeposits) {
    StartupUtil.setupInitialState(recentChainData, 0, null, validatorKeys, signDeposits);
  }

  public void initializeStorage() {
    initializeStorage(recentChainData);
  }

  public void initializeStorage(final RecentChainData recentChainData) {
    initializeStorage(recentChainData, validatorKeys, signDeposits);
  }

  public void setSlot(final UnsignedLong currentSlot) {
    checkState(!recentChainData.isPreGenesis(), "Cannot set current slot before genesis");
    final UnsignedLong secPerSlot = UnsignedLong.valueOf(Constants.SECONDS_PER_SLOT);
    final UnsignedLong time = recentChainData.getGenesisTime().plus(currentSlot.times(secPerSlot));
    setTime(time);
  }

  public void setTime(final UnsignedLong time) {
    checkState(!recentChainData.isPreGenesis(), "Cannot set time before genesis");
    final Transaction tx = recentChainData.startStoreTransaction();
    tx.setTime(time);
    tx.commit().join();
  }

  public SignedBeaconBlock createBlockAtSlot(final UnsignedLong slot) throws Exception {
    return createBlockAtSlot(slot, true);
  }

  public SignedBeaconBlock createAndImportBlockAtSlot(final long slot) throws Exception {
    return createAndImportBlockAtSlot(UnsignedLong.valueOf(slot));
  }

  public SignedBeaconBlock createAndImportBlockAtSlot(
      final UnsignedLong slot, List<Attestation> attestations) throws Exception {
    Optional<SSZList<Attestation>> sszList =
        attestations.isEmpty()
            ? Optional.empty()
            : Optional.of(
                SSZList.createMutable(attestations, Constants.MAX_ATTESTATIONS, Attestation.class));

    return createAndImportBlockAtSlot(slot, sszList);
  }

  public SignedBeaconBlock createAndImportBlockAtSlot(
      final UnsignedLong slot, Optional<SSZList<Attestation>> attestations) throws Exception {
    final SignedBeaconBlock block = createBlockAndStateAtSlot(slot, true, attestations).getBlock();
    setSlot(slot);
    final Transaction transaction = recentChainData.startStoreTransaction();
    final BlockImportResult importResult =
        ForkChoiceUtil.on_block(transaction, block, stateTransition, new StubForkChoiceStrategy());
    if (!importResult.isSuccessful()) {
      throw new IllegalStateException(
          "Produced an invalid block ( reason "
              + importResult.getFailureReason().name()
              + ") at slot "
              + slot
              + ": "
              + block);
    }
    final SafeFuture<Void> result = transaction.commit();
    if (!result.isDone() || result.isCompletedExceptionally()) {
      throw new IllegalStateException(
          "Transaction did not commit immediately. Are you using a disk storage backed ChainStorageClient without having storage running?");
    }
    recentChainData.updateBestBlock(
        block.getMessage().hash_tree_root(), block.getMessage().getSlot());
    return importResult.getBlock();
  }

  public SignedBeaconBlock createAndImportBlockAtSlot(final UnsignedLong slot) throws Exception {
    return createAndImportBlockAtSlot(slot, Optional.empty());
  }

  public SignedBeaconBlock createBlockAtSlotFromInvalidProposer(final UnsignedLong slot)
      throws Exception {
    return createBlockAtSlot(slot, false);
  }

  public SignedBeaconBlock createBlockAtSlot(final UnsignedLong slot, boolean withValidProposer)
      throws Exception {
    return createBlockAndStateAtSlot(slot, withValidProposer).getBlock();
  }

  public SignedBlockAndState createBlockAndStateAtSlot(
      final UnsignedLong slot, boolean withValidProposer) throws Exception {
    return createBlockAndStateAtSlot(slot, withValidProposer, Optional.empty());
  }

  private SignedBlockAndState createBlockAndStateAtSlot(
      final UnsignedLong slot,
      boolean withValidProposer,
      Optional<SSZList<Attestation>> attestations)
      throws Exception {
    checkState(
        withValidProposer || validatorKeys.size() > 1,
        "Must have >1 validator in order to create a block from an invalid proposer.");
    final Bytes32 bestBlockRoot = recentChainData.getBestBlockRoot().orElseThrow();
    final BeaconBlock bestBlock = recentChainData.getStore().getBlock(bestBlockRoot);
    final BeaconState preState = recentChainData.getBestBlockRootState().orElseThrow();
    checkArgument(bestBlock.getSlot().compareTo(slot) < 0, "Slot must be in the future.");

    final int correctProposerIndex = blockCreator.getProposerIndexForSlot(preState, slot);
    final int proposerIndex =
        withValidProposer ? correctProposerIndex : getWrongProposerIndex(correctProposerIndex);

    final MessageSignerService signer = getSigner(proposerIndex);
    if (attestations.isPresent()) {
      return blockCreator.createBlockWithAttestations(
          signer, slot, preState, bestBlockRoot, attestations.get());
    } else {
      return blockCreator.createEmptyBlock(signer, slot, preState, bestBlockRoot);
    }
  }

  public void finalizeChainAtEpoch(final UnsignedLong epoch) throws Exception {
    if (recentChainData.getStore().getFinalizedCheckpoint().getEpoch().compareTo(epoch) >= 0) {
      throw new Exception("Chain already finalized at this or higher epoch");
    }

    AttestationGenerator attestationGenerator = new AttestationGenerator(validatorKeys);
    createAndImportBlockAtSlot(
        recentChainData.getBestSlot().plus(UnsignedLong.valueOf(MIN_ATTESTATION_INCLUSION_DELAY)));

    while (recentChainData.getStore().getFinalizedCheckpoint().getEpoch().compareTo(epoch) < 0) {

      BeaconState headState =
          recentChainData
              .getStore()
              .getBlockState(recentChainData.getBestBlockRoot().orElseThrow());
      BeaconBlock headBlock =
          recentChainData.getStore().getBlock(recentChainData.getBestBlockRoot().orElseThrow());
      UnsignedLong slot = recentChainData.getBestSlot();
      SSZList<Attestation> currentSlotAssignments =
          SSZList.createMutable(
              attestationGenerator.getAttestationsForSlot(headState, headBlock, slot),
              Constants.MAX_ATTESTATIONS,
              Attestation.class);
      createAndImportBlockAtSlot(
          recentChainData.getBestSlot().plus(UnsignedLong.ONE),
          Optional.of(currentSlotAssignments));
    }
  }

  public List<BLSKeyPair> getValidatorKeys() {
    return validatorKeys;
  }

  public int getWrongProposerIndex(final int actualProposerIndex) {
    return actualProposerIndex == 0 ? 1 : actualProposerIndex - 1;
  }

  public MessageSignerService getSigner(final int proposerIndex) {
    return new TestMessageSignerService(validatorKeys.get(proposerIndex));
  }
}
