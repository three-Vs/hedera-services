package com.hedera.services.context.init;

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

import com.hedera.services.ServicesState;

import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class ServicesInitFlow {
	private final StateInitializationFlow stateFlow;
	private final StoreInitializationFlow storeFlow;
	private final EntitiesInitializationFlow entitiesFlow;

	@Inject
	public ServicesInitFlow(
			StateInitializationFlow stateFlow,
			StoreInitializationFlow storeFlow,
			EntitiesInitializationFlow entitiesFlow
	) {
		this.stateFlow = stateFlow;
		this.storeFlow = storeFlow;
		this.entitiesFlow = entitiesFlow;
	}

	public void runWith(ServicesState activeState) {
		stateFlow.runWith(activeState);
		storeFlow.run();
		entitiesFlow.run();
	}
}
