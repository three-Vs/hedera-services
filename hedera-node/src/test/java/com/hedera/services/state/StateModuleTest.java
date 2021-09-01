package com.hedera.services.state;

import com.hedera.services.context.properties.NodeLocalProperties;
import com.hedera.services.context.properties.PropertySource;
import com.hedera.services.keys.LegacyEd25519KeyReader;
import com.hedera.services.store.schedule.ScheduleStore;
import com.hedera.services.store.tokens.TokenStore;
import com.hedera.services.store.tokens.views.UniqTokenViewFactory;
import com.swirlds.common.CommonUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.Charset;

import static com.hedera.services.state.StateModule.provideStateViews;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;


@ExtendWith(MockitoExtension.class)
class StateModuleTest {
	@Mock
	private TokenStore tokenStore;
	@Mock
	private ScheduleStore scheduleStore;
	@Mock
	private NodeLocalProperties nodeLocalProperties;
	@Mock
	private UniqTokenViewFactory uniqTokenViewFactory;
	@Mock
	private StateAccessor workingState;
	@Mock
	private LegacyEd25519KeyReader b64KeyReader;
	@Mock
	private PropertySource properties;

	@Test
	void providesDefaultCharset() {
		// expect:
		assertEquals(Charset.defaultCharset(), StateModule.provideNativeCharset().get());
	}

	@Test
	void canGetSha384() {
		// expect:
		assertDoesNotThrow(() -> StateModule.provideDigestFactory().forName("SHA-384"));
	}

	@Test
	void notificationEngineAvail() {
		// expect:
		assertDoesNotThrow(() -> StateModule.provideNotificationEngine().get());
	}

	@Test
	void viewUsesWorkingStateChildren() {
		// given:
		final var viewFactory =
				provideStateViews(tokenStore, scheduleStore, nodeLocalProperties, uniqTokenViewFactory, workingState);

		// when:
		viewFactory.get();

		// then:
		verify(workingState).children();
	}

	@Test
	void looksUpExpectedKey() {
		// setup:
		final var keystoreLoc = "somewhere";
		final var storeName = "far";
		final var keyBytes = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa".getBytes();

		given(properties.getStringProperty("bootstrap.genesisB64Keystore.path")).willReturn(keystoreLoc);
		given(properties.getStringProperty("bootstrap.genesisB64Keystore.keyName")).willReturn(storeName);
		given(b64KeyReader.hexedABytesFrom(keystoreLoc, storeName)).willReturn(CommonUtils.hex(keyBytes));

		// when:
		final var keySupplier = StateModule.provideSystemFileKey(b64KeyReader, properties);
		// and:
		final var key = keySupplier.get();

		// then:
		assertArrayEquals(keyBytes, key.getEd25519());
	}
}