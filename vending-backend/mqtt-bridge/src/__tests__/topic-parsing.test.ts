/**
 * Tests for MQTT topic parsing.
 *
 * Verifies that the regex used in the MQTT bridge main module correctly
 * matches and extracts subdomain and event type from device topics.
 */

import { describe, it, expect } from "vitest";

/**
 * The same regex used in the bridge's index.ts for topic matching.
 * Copied here so we can test it in isolation without importing the
 * full bridge module (which would attempt to connect to MQTT).
 */
const TOPIC_REGEX = /^domain\.panamavendingmachines\.com\/(\d+)\/(sale|status|paxcounter|dex)$/;

describe("MQTT Topic Parsing", () => {
  it("matches a sale topic", () => {
    const match = "domain.panamavendingmachines.com/123456/sale".match(TOPIC_REGEX);
    expect(match).not.toBeNull();
    expect(match![1]).toBe("123456");
    expect(match![2]).toBe("sale");
  });

  it("matches a status topic", () => {
    const match = "domain.panamavendingmachines.com/789/status".match(TOPIC_REGEX);
    expect(match).not.toBeNull();
    expect(match![1]).toBe("789");
    expect(match![2]).toBe("status");
  });

  it("matches a paxcounter topic", () => {
    const match = "domain.panamavendingmachines.com/42/paxcounter".match(TOPIC_REGEX);
    expect(match).not.toBeNull();
    expect(match![1]).toBe("42");
    expect(match![2]).toBe("paxcounter");
  });

  it("matches a dex topic", () => {
    const match = "domain.panamavendingmachines.com/100/dex".match(TOPIC_REGEX);
    expect(match).not.toBeNull();
    expect(match![1]).toBe("100");
    expect(match![2]).toBe("dex");
  });

  it("does not match topics with non-numeric subdomains", () => {
    const match = "domain.panamavendingmachines.com/abc/sale".match(TOPIC_REGEX);
    expect(match).toBeNull();
  });

  it("does not match unknown event types", () => {
    const match = "domain.panamavendingmachines.com/123/unknown".match(TOPIC_REGEX);
    expect(match).toBeNull();
  });

  it("does not match device credit topics (different pattern)", () => {
    const match = "123456.panamavendingmachines.com/credit".match(TOPIC_REGEX);
    expect(match).toBeNull();
  });

  it("does not match partial topics", () => {
    const match = "domain.panamavendingmachines.com/123".match(TOPIC_REGEX);
    expect(match).toBeNull();
  });

  it("does not match topics with extra segments", () => {
    const match = "domain.panamavendingmachines.com/123/sale/extra".match(TOPIC_REGEX);
    expect(match).toBeNull();
  });
});
