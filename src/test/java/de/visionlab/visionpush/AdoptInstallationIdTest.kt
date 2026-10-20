package de.visionlab.visionpush

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Unit tests for the Unity-migration identity seed path: [VisionPush.adoptInstallationId]
 * (US-14 W0, IDENTITY) and the [DeviceRegistrar.adoptAnonymousId] / [DeviceRegistrar.seedAnonymousId]
 * it delegates to. A player moving from the Unity REST bridge to this native core must keep
 * its existing installation id instead of registering as a brand-new device.
 *
 * Covers both call timings: before [VisionPush.initialize] (no [DeviceRegistrar] instance yet
 * — the seed writes directly into the store) and after it (the live registrar's own store).
 */
@RunWith(RobolectricTestRunner::class)
class AdoptInstallationIdTest {

    private val context: android.content.Context
        // RuntimeEnvironment ships with Robolectric — no extra androidx.test:core dep.
        get() = RuntimeEnvironment.getApplication()

    private val config = VisionPushConfig(
        appId = "11111111-1111-1111-1111-111111111111",
        apiKey = "vp_test_key",
        apiBaseUrl = "https://api.visionpush.test/v1",
    )

    private fun newRegistrar() = DeviceRegistrar(context, config, ApiClient(config))

    @Before
    fun setUp() {
        VisionPush.resetForTesting()
    }

    @After
    fun tearDown() {
        VisionPush.resetForTesting()
    }

    // ── Before VisionPush.initialize() (no DeviceRegistrar instance yet) ────────

    @Test
    fun adoptWithNoExistingIdSeedsAndReturnsIt() {
        val id = "a1a1a1a1-1111-2222-3333-444444444444"

        val result = VisionPush.adoptInstallationId(context, id)

        assertEquals(id, result)
        // A DeviceRegistrar created afterwards must pick up the seeded id instead of
        // minting a new one — this is the whole point of the migration seed.
        val registrar = newRegistrar()
        assertEquals(id, registrar.existingAnonymousId)
        assertEquals(id, registrar.anonymousId)
    }

    @Test
    fun adoptWithAnExistingIdIsANoOpAndReturnsTheFirstId() {
        val first = "a1a1a1a1-1111-2222-3333-444444444444"
        val second = "b2b2b2b2-5555-6666-7777-888888888888"

        assertEquals(first, VisionPush.adoptInstallationId(context, first))
        val result = VisionPush.adoptInstallationId(context, second)

        assertEquals("an already-seeded id must never be overwritten", first, result)
        assertEquals(first, newRegistrar().existingAnonymousId)
    }

    @Test
    fun adoptWithAnInvalidUuidThrowsAndPersistsNothing() {
        assertThrows(VisionPushError.InvalidArgument::class.java) {
            VisionPush.adoptInstallationId(context, "not-a-uuid")
        }

        assertNull("the rejected value must never be persisted", newRegistrar().existingAnonymousId)
    }

    @Test
    fun adoptNormalizesUuidCasingAndWhitespaceFreeForm() {
        val upper = "A1A1A1A1-1111-2222-3333-444444444444"

        val result = VisionPush.adoptInstallationId(context, upper)

        assertEquals(upper.lowercase(), result)
        assertEquals(result, newRegistrar().existingAnonymousId)
    }

    // ── After VisionPush.initialize() (DeviceRegistrar instance already exists) ──

    @Test
    fun adoptAfterInitializeWithNoRegisteredIdSeedsTheLiveRegistrar() {
        VisionPush.pushTokenProvider = { null } // never touch Firebase / network
        VisionPush.initializeWithConfig(context, config)
        val id = "c3c3c3c3-1111-2222-3333-444444444444"

        val result = VisionPush.adoptInstallationId(context, id)

        assertEquals(id, result)
        assertEquals(id, VisionPush.internalDeviceRegistrar?.existingAnonymousId)
    }

    @Test
    fun adoptAfterInitializeWithAnAlreadyMintedIdIsANoOp() {
        VisionPush.pushTokenProvider = { null }
        VisionPush.initializeWithConfig(context, config)
        val registrar = requireNotNull(VisionPush.internalDeviceRegistrar)
        val minted = registrar.anonymousId // mints an id locally

        val result = VisionPush.adoptInstallationId(context, "d4d4d4d4-1111-2222-3333-444444444444")

        assertEquals("must never override an identity the registrar already minted", minted, result)
        assertEquals(minted, registrar.existingAnonymousId)
    }

    // ── DeviceRegistrar.adoptAnonymousId instance method directly ───────────────

    @Test
    fun registrarAdoptAnonymousIdDirectlySeedsOnceAndIsIdempotentAfter() {
        val registrar = newRegistrar()
        val id = "e5e5e5e5-1111-2222-3333-444444444444"

        assertEquals(id, registrar.adoptAnonymousId(id))
        assertEquals(id, registrar.adoptAnonymousId("f6f6f6f6-1111-2222-3333-444444444444"))
        assertEquals(id, registrar.anonymousId)
    }

    @Test
    fun registrarAdoptAnonymousIdRejectsAnInvalidUuid() {
        val registrar = newRegistrar()

        assertThrows(VisionPushError.InvalidArgument::class.java) {
            registrar.adoptAnonymousId("00000000-0000-0000-0000-00000000000z")
        }
        assertNull(registrar.existingAnonymousId)
    }
}
