package com.hedera.services.txns.contract.operation;

/*
 * -
 * ‌
 * Hedera Services Node
 * ​
 * Copyright (C) 2018 - 2021 Hedera Hashgraph, LLC
 * ​
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ‍
 *
 */

import com.hedera.services.contracts.execution.SoliditySigsVerifier;
import com.hedera.services.utils.EntityIdUtils;
import org.hyperledger.besu.evm.EVM;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.hyperledger.besu.evm.operation.CallOperation;

import javax.inject.Inject;
import java.util.Optional;
import java.util.Set;

public class HederaCallOperation extends CallOperation {
	private final SoliditySigsVerifier sigsVerifier;

	@Inject
	public HederaCallOperation(
			SoliditySigsVerifier sigsVerifier,
			GasCalculator gasCalculator) {
		super(gasCalculator);
		this.sigsVerifier = sigsVerifier;
	}

	@Override
	public OperationResult execute(MessageFrame frame, EVM evm) {
		final var account = frame.getWorldUpdater().get(to(frame));
		if (account == null) {
			return new OperationResult(
					Optional.of(cost(frame)), Optional.of(HederaExceptionalHaltReason.INVALID_SOLIDITY_ADDRESS));
		}

		final var accountId = EntityIdUtils.accountParsedFromSolidityAddress(account.getAddress().toArray());
		if (!sigsVerifier.allRequiredKeysAreActive(Set.of(accountId))) {
			return new OperationResult(
					Optional.of(cost(frame)), Optional.of(HederaExceptionalHaltReason.INVALID_SIGNATURE)
			);
		}

		return super.execute(frame, evm);
	}
}