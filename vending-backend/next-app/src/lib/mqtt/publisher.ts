/**
 * MQTT publisher utility for sending messages to the local Mosquitto broker.
 *
 * The vending backend publishes credit messages to specific device topics
 * so that ESP32 devices can receive remote credit pushes. This module
 * handles the connection lifecycle: connect, publish, disconnect.
 *
 * The MQTT broker runs as a sibling Docker container (`mosquitto` service)
 * accessible at `mqtt://mosquitto:1883` from within the Docker network.
 */

import mqtt from "mqtt";

/**
 * Returns the MQTT broker URL from the environment, defaulting to
 * the Docker-internal Mosquitto address.
 */
function getBrokerUrl(): string {
  return process.env.MQTT_BROKER_URL || "mqtt://mosquitto:1883";
}

/**
 * Publishes a binary payload to the specified MQTT topic.
 *
 * Opens a short-lived connection to the local Mosquitto broker,
 * publishes the message, then disconnects. This is acceptable for
 * the credit-push use case where publishes are infrequent and
 * latency is not critical (the user is waiting on an HTTP response,
 * not real-time streaming).
 *
 * @param topic   - The full MQTT topic (e.g. "123456.panamavendingmachines.com/credit")
 * @param payload - The binary message payload (19-byte XOR-encrypted buffer)
 * @returns A promise that resolves when the message has been published
 * @throws If the connection or publish fails
 */
export async function publishToMqtt(
  topic: string,
  payload: Buffer | Uint8Array
): Promise<void> {
  const brokerUrl = getBrokerUrl();

  const client = mqtt.connect(brokerUrl, {
    /** Short connect timeout since the broker is local */
    connectTimeout: 5000,
    /** Do not attempt automatic reconnection for one-shot publishes */
    reconnectPeriod: 0,
  });

  return new Promise<void>((resolve, reject) => {
    /** Handle connection errors */
    client.on("error", (err) => {
      client.end(true);
      reject(new Error(`MQTT connection error: ${err.message}`));
    });

    /** Once connected, publish and disconnect */
    client.on("connect", () => {
      client.publish(topic, Buffer.from(payload), { qos: 0 }, (err) => {
        if (err) {
          client.end(true);
          reject(new Error(`MQTT publish error: ${err.message}`));
        } else {
          client.end(false, {}, () => {
            resolve();
          });
        }
      });
    });
  });
}
