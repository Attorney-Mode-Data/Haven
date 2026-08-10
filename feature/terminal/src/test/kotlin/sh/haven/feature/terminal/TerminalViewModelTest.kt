package sh.haven.feature.terminal

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import sh.haven.core.et.EtSessionManager
import sh.haven.core.local.LocalSessionManager
import sh.haven.core.mosh.MoshSessionManager
import sh.haven.core.reticulum.ReticulumSessionManager
import sh.haven.core.ssh.SshClient
import sh.haven.core.ssh.SshSessionManager

class TerminalViewModelTest {

    private lateinit var sessionManager: SshSessionManager
    private lateinit var reticulumSessionManager: ReticulumSessionManager
    private lateinit var moshSessionManager: MoshSessionManager
    private lateinit var etSessionManager: EtSessionManager
    private lateinit var localSessionManager: LocalSessionManager
    private lateinit var btSerialSessionManager: sh.haven.core.btserial.BtSerialSessionManager
    private lateinit var bleSerialSessionManager: sh.haven.core.bleserial.BleSerialSessionManager
    private lateinit var bleSerialSessions:
        MutableStateFlow<Map<String, sh.haven.core.bleserial.BleSerialSessionManager.SessionState>>
    private lateinit var usbSerialSessionManager: sh.haven.core.usbserial.UsbSerialSessionManager
    private lateinit var sshEmulatorOwner: SshTerminalEmulatorOwner
    private lateinit var viewModel: TerminalViewModel

    @Before
    fun setUp() {
        sessionManager = SshSessionManager(mockk(relaxed = true), mockk(relaxed = true))
        reticulumSessionManager = mockk<ReticulumSessionManager>(relaxed = true) {
            every { sessions } returns MutableStateFlow(emptyMap())
        }
        moshSessionManager = mockk<MoshSessionManager>(relaxed = true) {
            every { sessions } returns MutableStateFlow(emptyMap())
        }
        etSessionManager = mockk<EtSessionManager>(relaxed = true) {
            every { sessions } returns MutableStateFlow(emptyMap())
        }
        localSessionManager = mockk<LocalSessionManager>(relaxed = true) {
            every { sessions } returns MutableStateFlow(emptyMap())
        }
        btSerialSessionManager = mockk(relaxed = true) {
            every { sessions } returns MutableStateFlow(emptyMap())
        }
        // #465: stubbed ONCE here, then driven by mutating the flow. The
        // ViewModel's init launches collectors that read these mocks, so a
        // later `every { sessions } ...` would put MockK into recording mode
        // while another thread is invoking the same mock — which throws
        // MockKException rather than failing an assertion, and only when the
        // collectors happen to be live (CI, loaded runner).
        bleSerialSessions = MutableStateFlow(emptyMap())
        bleSerialSessionManager = mockk(relaxed = true) {
            every { sessions } returns bleSerialSessions
        }
        usbSerialSessionManager = mockk(relaxed = true) {
            every { sessions } returns MutableStateFlow(emptyMap())
        }
        // No connect-time bundle in unit tests (the real emulator needs JNI),
        // so the SSH adopt branch skips — matching the old isReadyForTerminal skip.
        sshEmulatorOwner = mockk(relaxed = true) {
            every { bundleFor(any()) } returns null
        }
        viewModel = TerminalViewModel(
            mockk(relaxed = true),
            sessionManager,
            mockk(relaxed = true), // SshSessionAttacher
            reticulumSessionManager,
            moshSessionManager,
            etSessionManager,
            btSerialSessionManager,
            bleSerialSessionManager,
            usbSerialSessionManager,
            mockk<sh.haven.core.usb.UsbBroker>(relaxed = true),
            localSessionManager,
            mockk(relaxed = true), // HostKeyVerifier
            mockk(relaxed = true), // FidoAuthenticator
            mockk(relaxed = true), // UserPreferencesRepository
            mockk(relaxed = true), // ConnectionRepository
            mockk(relaxed = true), // TunnelResolver
            sh.haven.core.data.agent.AgentUiCommandBus(),
            sh.haven.core.data.message.UserMessageBus(),
            mockk(relaxed = true),
            sh.haven.feature.terminal.agent.TerminalSessionRegistry(),
            sshEmulatorOwner,
            mockk(relaxed = true), // BarcodeDecoder
            mockk(relaxed = true), // TextRecognizer
        )
    }

    @Test
    fun `initially has no tabs`() {
        assertEquals(0, viewModel.tabs.value.size)
        assertEquals(0, viewModel.activeTabIndex.value)
    }

    @Test
    fun `syncSessions with no sessions produces no tabs`() {
        runBlocking { viewModel.syncSessions() }
        assertEquals(0, viewModel.tabs.value.size)
    }

    @Test
    fun `syncSessions skips CONNECTING sessions`() {
        val client = mockk<SshClient>(relaxed = true)
        sessionManager.registerSession("profile1", "Server", client)
        // Status is CONNECTING, no shell channel

        runBlocking { viewModel.syncSessions() }
        assertEquals(0, viewModel.tabs.value.size)
    }

    @Test
    fun `syncSessions skips CONNECTED sessions without shell channel`() {
        val client = mockk<SshClient>(relaxed = true)
        val sessionId = sessionManager.registerSession("profile1", "Server", client)
        sessionManager.updateStatus(sessionId, SshSessionManager.SessionState.Status.CONNECTED)
        // No shell channel attached

        runBlocking { viewModel.syncSessions() }
        assertEquals(0, viewModel.tabs.value.size)
    }

    @Test
    fun `selectTab with no tabs is no-op`() {
        viewModel.selectTab(2)
        assertEquals(0, viewModel.activeTabIndex.value)
    }

    @Test
    fun `closeTab removes from session manager`() {
        val client = mockk<SshClient>(relaxed = true)
        val sessionId = sessionManager.registerSession("profile1", "Server", client)
        viewModel.closeTab(sessionId)

        assertEquals(null, sessionManager.getSession(sessionId))
    }

    @Test
    fun `closeTab tears down a BLE-serial session`() {
        // Regression: removeTabAndSync only knew SSH/mosh/et/local/reticulum, so a
        // serial session fell through to reticulum (no-op), stayed alive, and
        // syncSessions rebuilt its tab — the tab wouldn't close.
        val sessionId = "ble-1"
        // Drive the already-stubbed flow rather than re-stubbing the mock (#465).
        bleSerialSessions.value = mapOf(sessionId to mockk(relaxed = true))

        viewModel.closeTab(sessionId)

        verify { bleSerialSessionManager.removeSession(sessionId) }
    }

    @Test
    fun `closeSession removes all sessions for profile`() {
        val c1 = mockk<SshClient>(relaxed = true)
        val c2 = mockk<SshClient>(relaxed = true)
        sessionManager.registerSession("profile1", "Server", c1)
        sessionManager.registerSession("profile1", "Server", c2)

        viewModel.closeSession("profile1")

        assertEquals(0, sessionManager.getSessionsForProfile("profile1").size)
    }

    @Test
    fun `selectTabByProfileId with no matching tab is no-op`() {
        viewModel.selectTabByProfileId("nonexistent")
        assertEquals(0, viewModel.activeTabIndex.value)
    }

    @Test
    fun `selectTabBySessionId with no matching tab is no-op`() {
        viewModel.selectTabBySessionId("nonexistent")
        assertEquals(0, viewModel.activeTabIndex.value)
    }

    // The toolbar dispatches its nav keys by key code, not bytes, so a tapped
    // Ctrl only reaches them through this mask. It was hardcoded to 0, which is
    // why Ctrl+End sent a bare End.
    @Test
    fun `a tapped Ctrl reaches the toolbar's own keys as the vterm mask`() {
        assertEquals(0, viewModel.toolbarModifierMask())

        viewModel.toggleCtrl()
        assertEquals(4, viewModel.toolbarModifierMask())

        viewModel.toggleAlt()
        assertEquals(6, viewModel.toolbarModifierMask())
    }

    @Test
    fun `a one-shot Ctrl is spent by the keystroke that used it`() {
        viewModel.toggleCtrl()
        viewModel.clearStickyModifiers()

        assertEquals(false, viewModel.ctrlActive.value)
        assertEquals(0, viewModel.toolbarModifierMask())
    }

    // #522: Ctrl, Ctrl locks. The reporter's case is Claude Code's double
    // Ctrl+C to exit, which is exactly the two keystrokes a lock serves.
    @Test
    fun `a second Ctrl tap locks it, and it survives one keystroke`() {
        viewModel.toggleCtrl()
        viewModel.toggleCtrl()

        assertEquals(true, viewModel.ctrlLocked.value)
        assertEquals(true, viewModel.ctrlActive.value)

        viewModel.clearStickyModifiers()

        assertEquals("a lock is not spent by one keystroke", true, viewModel.ctrlActive.value)
        assertEquals(4, viewModel.toolbarModifierMask())
    }

    @Test
    fun `a locked Ctrl releases itself after two keystrokes`() {
        viewModel.toggleCtrl()
        viewModel.toggleCtrl()

        viewModel.clearStickyModifiers()
        viewModel.clearStickyModifiers()

        assertEquals(false, viewModel.ctrlLocked.value)
        assertEquals(false, viewModel.ctrlActive.value)
        assertEquals(0, viewModel.toolbarModifierMask())
    }

    @Test
    fun `a third Ctrl tap unlocks it immediately`() {
        viewModel.toggleCtrl()
        viewModel.toggleCtrl()
        viewModel.toggleCtrl()

        assertEquals(false, viewModel.ctrlLocked.value)
        assertEquals(false, viewModel.ctrlActive.value)
    }

    @Test
    fun `locking Ctrl again after a release starts a fresh pair of keystrokes`() {
        viewModel.toggleCtrl()
        viewModel.toggleCtrl()
        viewModel.clearStickyModifiers()
        viewModel.clearStickyModifiers()

        viewModel.toggleCtrl()
        viewModel.toggleCtrl()
        viewModel.clearStickyModifiers()

        assertEquals("the use count must reset with the new lock", true, viewModel.ctrlActive.value)
    }

    @Test
    fun `Alt locks independently of Ctrl`() {
        viewModel.toggleAlt()
        viewModel.toggleAlt()
        viewModel.toggleCtrl()

        viewModel.clearStickyModifiers()

        assertEquals("the one-shot Ctrl is spent", false, viewModel.ctrlActive.value)
        assertEquals("the locked Alt is not", true, viewModel.altActive.value)
        assertEquals(2, viewModel.toolbarModifierMask())
    }
}
