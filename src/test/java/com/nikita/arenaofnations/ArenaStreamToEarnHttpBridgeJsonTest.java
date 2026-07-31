package com.nikita.arenaofnations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;

class ArenaStreamToEarnHttpBridgeJsonTest {
	@Test
	void quotesPartnerStyleUnquotedCoinsPlaceholder() {
		String raw = "{\"token\":\"T\",\"viewerId\":\"{uniqueid}\",\"coins\":{coins}}";
		String quoted = ArenaStreamToEarnHttpBridge.quoteUnquotedStreamToEarnPlaceholders(raw);
		assertEquals("{\"token\":\"T\",\"viewerId\":\"{uniqueid}\",\"coins\":\"{coins}\"}", quoted);
	}

	@Test
	void parsesPartnerStyleGiftBodyAsObject() {
		String raw = "{\n  \"token\": \"AON_S2E_2026_K7P4M9Q2\",\n  \"viewerId\": \"{uniqueid}\",\n  \"coins\": {coins}\n}";
		JsonObject obj = ArenaStreamToEarnHttpBridge.parseCompatJsonRoot(raw).getAsJsonObject();
		assertEquals("AON_S2E_2026_K7P4M9Q2", obj.get("token").getAsString());
		assertEquals("{uniqueid}", obj.get("viewerId").getAsString());
		assertEquals("{coins}", obj.get("coins").getAsString());
	}

	@Test
	void unwrapsDoubleEncodedJsonObject() {
		String inner = "{\"token\":\"T\",\"viewerId\":\"u1\",\"coins\":1}";
		String wrapped = "\"" + inner.replace("\"", "\\\"") + "\"";
		JsonObject obj = ArenaStreamToEarnHttpBridge.parseCompatJsonRoot(wrapped).getAsJsonObject();
		assertEquals("T", obj.get("token").getAsString());
		assertEquals(1, obj.get("coins").getAsInt());
	}

	@Test
	void leavesAlreadyQuotedCoinsPlaceholderAlone() {
		String raw = "{\"coins\":\"{coins}\"}";
		assertEquals(raw, ArenaStreamToEarnHttpBridge.quoteUnquotedStreamToEarnPlaceholders(raw));
		assertTrue(ArenaStreamToEarnHttpBridge.parseCompatJsonRoot(raw).isJsonObject());
	}

	@Test
	void stripsUtf8BomBytes() {
		byte[] json = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
		byte[] withBom = new byte[json.length + 3];
		withBom[0] = (byte) 0xEF;
		withBom[1] = (byte) 0xBB;
		withBom[2] = (byte) 0xBF;
		System.arraycopy(json, 0, withBom, 3, json.length);
		byte[] stripped = ArenaStreamToEarnHttpBridge.stripUtf8Bom(withBom);
		assertEquals("{\"a\":1}", new String(stripped, StandardCharsets.UTF_8));
	}

	@Test
	void quotesMinMaxStylePlaceholder() {
		String raw = "{\"token\":\"T\",\"viewerId\":\"u\",\"coins\":{minmax}}";
		JsonObject obj = ArenaStreamToEarnHttpBridge.parseCompatJsonRoot(raw).getAsJsonObject();
		assertEquals("{minmax}", obj.get("coins").getAsString());
	}
}
