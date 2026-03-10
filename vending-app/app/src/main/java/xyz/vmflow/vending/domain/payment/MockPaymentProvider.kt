package xyz.vmflow.vending.domain.payment

import kotlinx.coroutines.delay
import java.util.UUID

/**
 * Mock payment provider that always succeeds.
 *
 * This implementation is used for development, testing, and demo purposes.
 * It simulates a 1-second payment processing delay and returns a successful
 * result with a randomly generated transaction ID.
 *
 * ## Behavior
 * - [processPayment]: Delays 1 second, then returns [PaymentResult.Success]
 * - [isAvailable]: Always returns `true`
 *
 * In a production environment, this would be replaced with a real payment
 * provider implementation (e.g., Stripe, Square) that handles actual
 * card processing and funds transfer.
 */
class MockPaymentProvider : PaymentProvider {

    companion object {
        /** Simulated processing delay in milliseconds */
        private const val PROCESSING_DELAY_MS = 1000L
    }

    /**
     * Simulates payment processing with a brief delay.
     *
     * Always returns success with a randomly generated UUID as the transaction ID.
     * The 1-second delay mimics the latency of a real payment gateway.
     *
     * @param amount The payment amount (not validated in mock mode).
     * @param currency The currency code (not validated in mock mode).
     * @return [PaymentResult.Success] with a random transaction ID.
     */
    override suspend fun processPayment(amount: Double, currency: String): PaymentResult {
        // Simulate network latency of a real payment processor
        delay(PROCESSING_DELAY_MS)

        return PaymentResult.Success(
            transactionId = UUID.randomUUID().toString()
        )
    }

    /**
     * Mock provider is always available.
     *
     * @return Always `true`.
     */
    override fun isAvailable(): Boolean = true
}
