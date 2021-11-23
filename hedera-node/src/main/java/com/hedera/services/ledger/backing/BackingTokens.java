package com.hedera.services.ledger.backing;

/*-
 * ‌
 * Hedera Services Node
 * ​
 * Copyright (C) 2018 - 2021 Hedera Hashgraph, LLC
 * ​
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ‍
 */

import com.hedera.services.state.merkle.MerkleToken;
import com.hedera.services.utils.EntityNum;
import com.hederahashgraph.api.proto.java.TokenID;
import com.swirlds.merkle.map.MerkleMap;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static com.hedera.services.utils.EntityNum.fromTokenId;

@Singleton
public class BackingTokens implements BackingStore<TokenID, MerkleToken> {
    private final Supplier<MerkleMap<EntityNum, MerkleToken>> delegate;

    @Inject
    public BackingTokens(Supplier<MerkleMap<EntityNum, MerkleToken>> delegate) {
        this.delegate = delegate;
    }

    @Override
    public MerkleToken getRef(TokenID id) {
        return delegate.get().getForModify(fromTokenId(id));
    }

    @Override
    public void put(TokenID id, MerkleToken token) {
        final var tokens = delegate.get();
        final var eId = fromTokenId(id);
        tokens.computeIfAbsent(eId, a -> tokens.put(a, token));
    }

    @Override
    public boolean contains(TokenID id) {
        return delegate.get().containsKey(EntityNum.fromTokenId(id));
    }

    @Override
    public void remove(TokenID id) {
        delegate.get().remove(fromTokenId(id));
    }

    @Override
    public Set<TokenID> idSet() {
        return delegate.get().keySet().stream()
                .map(EntityNum::toGrpcTokenId)
        .collect(Collectors.toSet());
    }

    @Override
    public MerkleToken getImmutableRef(TokenID id) {
        return delegate.get().get(fromTokenId(id));
    }
}
