package com.hedera.services.store.contracts;

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

import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.evm.Gas;
import org.hyperledger.besu.evm.worldstate.WorldUpdater;

import java.util.Map;

/**
 * Provides a stacked Hedera adapted world view. Utilised by {@link org.hyperledger.besu.evm.frame.MessageFrame} in
 * order to provide a layered view for read/writes of the state during EVM transaction execution
 */
public interface HederaWorldUpdater extends WorldUpdater {

	/**
	 * Allocates new Contract address based on the realm and shard of the sponsor
	 * IMPORTANT - The Id must be reclaimed if the MessageFrame reverts
	 *
	 * @param sponsor
	 * 		sponsor of the new contract
	 * @return newly generated contract {@link Address}
	 */
	Address allocateNewContractAddress(Address sponsor);

	/**
	 * Tracks who initiated the creation of a new account/contract/token. The caller becomes the "sponsor" and when
	 * hedera-specific information is needed for creation (admin key, memo, etc). the value is inherited from the
	 * sponsor.
	 *
	 * @return the sponsor map;
	 */
	Map<Address, Address> getSponsorMap();

	/**
	 * Returns the account with hedera information either from the in-process world state update or from the base world
	 * state.
	 *
	 * @param address
	 * 		the address of the account
	 * @return the hedera world state account
	 */
	HederaWorldState.WorldStateAccount getHederaAccount(Address address);

	/**
	 * Tracks how much Gas should be refunded to the sender account for the TX. SBH price is refunded for the first
	 * allocation of new contract storage in order to prevent double charging the client.
	 *
	 * @return the amount of Gas to refund;
	 */
	Gas getSbhRefund();

	/**
	 * Used to keep track of SBH gas refunds between all instances of HederaWorldUpdater. Lower level updaters should
	 * add the value of Gas to refund to their respective parent Updater on commit.
	 *
	 * @param refund
	 * 	the amount of Gas to refund;
	 */
	void addSbhRefund(Gas refund);
}
