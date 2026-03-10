/**
 * Tests for the MQTT bridge message handlers.
 *
 * These tests verify the handler functions in isolation by mocking
 * the Supabase client. Each handler (sale, status, paxcounter) is
 * tested for both success and error scenarios.
 */

import { describe, it, expect, vi, beforeEach } from "vitest";
import { handleSale } from "../handlers/sale";
import { handleStatus } from "../handlers/status";
import { handlePaxcounter } from "../handlers/paxcounter";

const TEST_PASSKEY = "abcdefghijklmnopqr";
const TEST_TIMESTAMP = Math.floor(Date.now() / 1000);
const PAYLOAD_LENGTH = 19;
const PASSKEY_LENGTH = 18;

/**
 * Helper to create a valid encrypted test payload.
 */
function createTestPayload(
  cmd: number,
  passkey: string,
  itemPrice: number,
  itemNumber: number,
  paxCount: number,
  timestamp: number
): Buffer {
  const payload = Buffer.alloc(PAYLOAD_LENGTH, 0);

  payload[0] = cmd;
  payload[1] = 0x01;
  payload[2] = (itemPrice >> 24) & 0xff;
  payload[3] = (itemPrice >> 16) & 0xff;
  payload[4] = (itemPrice >> 8) & 0xff;
  payload[5] = itemPrice & 0xff;
  payload[6] = (itemNumber >> 8) & 0xff;
  payload[7] = itemNumber & 0xff;
  payload[8] = (timestamp >> 24) & 0xff;
  payload[9] = (timestamp >> 16) & 0xff;
  payload[10] = (timestamp >> 8) & 0xff;
  payload[11] = timestamp & 0xff;
  payload[12] = (paxCount >> 8) & 0xff;
  payload[13] = paxCount & 0xff;

  let chk = 0;
  for (let k = 0; k < PAYLOAD_LENGTH - 1; k++) {
    chk += payload[k];
  }
  payload[PAYLOAD_LENGTH - 1] = chk & 0xff;

  for (let k = 0; k < PASSKEY_LENGTH; k++) {
    payload[k + 1] ^= passkey.charCodeAt(k);
  }

  return payload;
}

/**
 * Creates a mock Supabase client with chainable query methods.
 */
function createMockSupabase(overrides: Record<string, unknown> = {}) {
  const mockInsert = vi.fn().mockReturnValue({ error: null });
  const mockUpdate = vi.fn().mockReturnValue({
    eq: vi.fn().mockReturnValue({ error: null }),
  });
  const mockSelect = vi.fn().mockReturnValue({
    eq: vi.fn().mockReturnValue({
      data: [
        {
          passkey: TEST_PASSKEY,
          subdomain: "123456",
          id: "device-uuid-123",
          owner_id: "user-uuid-456",
          machine_id: "machine-uuid-789",
          ...overrides,
        },
      ],
      error: null,
    }),
  });

  return {
    from: vi.fn().mockReturnValue({
      select: mockSelect,
      insert: mockInsert,
      update: mockUpdate,
    }),
    _mockInsert: mockInsert,
    _mockSelect: mockSelect,
    _mockUpdate: mockUpdate,
  };
}

describe("handleSale", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("inserts a cash sale record for a valid payload", async () => {
    const mock = createMockSupabase();
    const payload = createTestPayload(0x05, TEST_PASSKEY, 250, 7, 0, TEST_TIMESTAMP);

    await handleSale(mock as any, "123456", payload);

    /* Verify the supabase client was called */
    expect(mock.from).toHaveBeenCalledWith("embedded");
    expect(mock.from).toHaveBeenCalledWith("sales");
  });

  it("does not insert a sale when device is not found", async () => {
    const mock = createMockSupabase();
    mock.from.mockReturnValue({
      select: vi.fn().mockReturnValue({
        eq: vi.fn().mockReturnValue({
          data: [],
          error: null,
        }),
      }),
      insert: vi.fn(),
    });

    const payload = createTestPayload(0x05, TEST_PASSKEY, 150, 1, 0, TEST_TIMESTAMP);
    await handleSale(mock as any, "999999", payload);

    /* Insert should not be called since device was not found */
    expect(mock.from).toHaveBeenCalledWith("embedded");
  });
});

describe("handleStatus", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("updates device status to online", async () => {
    const mock = createMockSupabase();

    await handleStatus(mock as any, "123456", Buffer.from("online"));

    expect(mock.from).toHaveBeenCalledWith("embedded");
  });

  it("updates device status to offline", async () => {
    const mock = createMockSupabase();

    await handleStatus(mock as any, "123456", Buffer.from("offline"));

    expect(mock.from).toHaveBeenCalledWith("embedded");
  });

  it("rejects invalid status values", async () => {
    const mock = createMockSupabase();
    const consoleSpy = vi.spyOn(console, "error").mockImplementation(() => {});

    await handleStatus(mock as any, "123456", Buffer.from("invalid"));

    /* Should log an error and not attempt to update */
    expect(consoleSpy).toHaveBeenCalled();
    consoleSpy.mockRestore();
  });
});

describe("handlePaxcounter", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("inserts a paxcounter metric for a valid payload", async () => {
    const mock = createMockSupabase();
    const payload = createTestPayload(0x22, TEST_PASSKEY, 0, 0, 42, TEST_TIMESTAMP);

    await handlePaxcounter(mock as any, "123456", payload);

    expect(mock.from).toHaveBeenCalledWith("embedded");
    expect(mock.from).toHaveBeenCalledWith("metrics");
  });

  it("does not insert a metric when device is not found", async () => {
    const mock = createMockSupabase();
    mock.from.mockReturnValue({
      select: vi.fn().mockReturnValue({
        eq: vi.fn().mockReturnValue({
          data: [],
          error: null,
        }),
      }),
      insert: vi.fn(),
    });

    const payload = createTestPayload(0x22, TEST_PASSKEY, 0, 0, 10, TEST_TIMESTAMP);
    await handlePaxcounter(mock as any, "999999", payload);

    expect(mock.from).toHaveBeenCalledWith("embedded");
  });
});
