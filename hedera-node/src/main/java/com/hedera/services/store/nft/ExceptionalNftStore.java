package com.hedera.services.store.nft;

import com.google.protobuf.ByteString;
import com.hedera.services.ledger.HederaLedger;
import com.hedera.services.ledger.TransactionalLedger;
import com.hedera.services.ledger.properties.AccountProperty;
import com.hedera.services.state.merkle.MerkleAccount;
import com.hedera.services.state.merkle.MerkleNftType;
import com.hedera.services.store.CreationResult;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.NftCreateTransactionBody;
import com.hederahashgraph.api.proto.java.NftID;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;

import java.util.function.Consumer;

public enum ExceptionalNftStore implements NftStore {
	NOOP_NFT_STORE;

	@Override
	public CreationResult<NftID> createProvisionally(NftCreateTransactionBody request, AccountID sponsor, long now) {
		throw new UnsupportedOperationException();
	}

	@Override
	public ResponseCodeEnum transferOwnership(NftID nft, ByteString serialNo, AccountID from, AccountID to) {
		throw new UnsupportedOperationException();
	}

	@Override
	public MerkleNftType get(NftID id) {
		throw new UnsupportedOperationException();
	}

	@Override
	public boolean exists(NftID id) {
		throw new UnsupportedOperationException();
	}

	@Override
	public void apply(NftID id, Consumer<MerkleNftType> change) {
		throw new UnsupportedOperationException();
	}

	@Override
	public void setHederaLedger(HederaLedger ledger) {
		throw new UnsupportedOperationException();
	}

	@Override
	public void setAccountsLedger(TransactionalLedger<AccountID, AccountProperty, MerkleAccount> accountsLedger) {
		throw new UnsupportedOperationException();
	}

	@Override
	public void rebuildViews() {
		throw new UnsupportedOperationException();
	}

	@Override
	public void commitCreation() {
		throw new UnsupportedOperationException();
	}

	@Override
	public void rollbackCreation() {
		throw new UnsupportedOperationException();
	}

	@Override
	public boolean isCreationPending() {
		throw new UnsupportedOperationException();
	}

	@Override
	public NftID resolve(NftID id) {
		throw new UnsupportedOperationException();
	}

	@Override
	public ResponseCodeEnum delete(NftID id) {
		throw new UnsupportedOperationException();
	}
}