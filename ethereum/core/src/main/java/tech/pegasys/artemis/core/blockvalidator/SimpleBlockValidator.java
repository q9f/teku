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

package tech.pegasys.artemis.core.blockvalidator;

import static tech.pegasys.artemis.datastructures.util.BeaconStateUtil.compute_signing_root;
import static tech.pegasys.artemis.datastructures.util.BeaconStateUtil.get_beacon_proposer_index;
import static tech.pegasys.artemis.datastructures.util.BeaconStateUtil.get_domain;
import static tech.pegasys.artemis.util.config.Constants.DOMAIN_BEACON_PROPOSER;

import org.apache.tuweni.bytes.Bytes;
import tech.pegasys.artemis.bls.BLSSignatureVerifier;
import tech.pegasys.artemis.bls.BLSSignatureVerifier.InvalidSignatureException;
import tech.pegasys.artemis.core.BlockProcessorUtil;
import tech.pegasys.artemis.core.StateTransitionException;
import tech.pegasys.artemis.core.exceptions.BlockProcessingException;
import tech.pegasys.artemis.datastructures.blocks.BeaconBlock;
import tech.pegasys.artemis.datastructures.blocks.BeaconBlockBody;
import tech.pegasys.artemis.datastructures.blocks.SignedBeaconBlock;
import tech.pegasys.artemis.datastructures.state.BeaconState;
import tech.pegasys.artemis.datastructures.state.Validator;
import tech.pegasys.artemis.util.async.SafeFuture;

/**
 * Base logic of a block validation
 *
 * <p>Delegates bls signature verifications to BLSSignatureVerifier instance Optionally may skip
 * some validations.
 */
public class SimpleBlockValidator implements BlockValidator {

  private final boolean verifyBlockSignature;
  private final boolean verifyBlockBody;
  private final boolean verifyPostStateRoot;
  private final BLSSignatureVerifier signatureVerifier;

  public SimpleBlockValidator() {
    this(true, true, true);
  }

  public SimpleBlockValidator(
      boolean verifyBlockSignature, boolean verifyBlockBody, boolean verifyPostStateRoot) {
    this(verifyBlockSignature, verifyBlockBody, verifyPostStateRoot, BLSSignatureVerifier.SIMPLE);
  }

  public SimpleBlockValidator(
      boolean verifyBlockSignature,
      boolean verifyBlockBody,
      boolean verifyPostStateRoot,
      BLSSignatureVerifier signatureVerifier) {
    this.verifyBlockSignature = verifyBlockSignature;
    this.verifyBlockBody = verifyBlockBody;
    this.verifyPostStateRoot = verifyPostStateRoot;
    this.signatureVerifier = signatureVerifier;
  }

  @Override
  public SafeFuture<BlockValidationResult> validatePreState(
      BeaconState preState, SignedBeaconBlock block) {
    try {
      if (verifyBlockSignature) {
        verify_block_signature(preState, block);
      }

      if (verifyBlockBody) {
        BeaconBlock blockMessage = block.getMessage();
        BeaconBlockBody blockBody = blockMessage.getBody();
        BlockProcessorUtil.verify_attestations(
            preState, blockBody.getAttestations(), signatureVerifier);
        BlockProcessorUtil.verify_randao(preState, blockMessage, signatureVerifier);
        BlockProcessorUtil.verify_proposer_slashings(
            preState, blockBody.getProposer_slashings(), signatureVerifier);
        BlockProcessorUtil.verify_voluntary_exits(
            preState, blockBody.getVoluntary_exits(), signatureVerifier);
      }
      return SafeFuture.completedFuture(new BlockValidationResult(true));
    } catch (BlockProcessingException | InvalidSignatureException e) {
      return SafeFuture.completedFuture(new BlockValidationResult(e));
    } catch (Exception e) {
      return SafeFuture.failedFuture(e);
    }
  }

  @Override
  public SafeFuture<BlockValidationResult> validatePostState(
      BeaconState postState, SignedBeaconBlock block) {
    if (verifyPostStateRoot
        && !block.getMessage().getState_root().equals(postState.hashTreeRoot())) {
      return SafeFuture.completedFuture(
          new BlockValidationResult(
              new StateTransitionException(
                  "Block state root does NOT match the calculated state root!\n"
                      + "Block state root: "
                      + block.getMessage().getState_root().toHexString()
                      + "New state root: "
                      + postState.hashTreeRoot().toHexString())));
    } else {
      return SafeFuture.completedFuture(new BlockValidationResult(true));
    }
  }

  private void verify_block_signature(final BeaconState state, SignedBeaconBlock signed_block)
      throws BlockProcessingException {
    final Validator proposer =
        state.getValidators().get(get_beacon_proposer_index(state, signed_block.getSlot()));
    final Bytes signing_root =
        compute_signing_root(signed_block.getMessage(), get_domain(state, DOMAIN_BEACON_PROPOSER));
    try {
      signatureVerifier.verifyAndThrow(
          proposer.getPubkey(), signing_root, signed_block.getSignature());
    } catch (InvalidSignatureException e) {
      throw new BlockProcessingException("Invalid block signature: " + signed_block);
    }
  }
}
