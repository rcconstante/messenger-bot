package com.pagerelay.bot

import com.pagerelay.bot.automation.MessengerNodeFinder
import com.pagerelay.bot.automation.NodeBounds
import com.pagerelay.bot.automation.ObservedCallScreenState
import com.pagerelay.bot.automation.UiNodeSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MessengerNodeFinderTest {

    @Test
    fun `finds audio call button and ignores video call button in chat toolbar`() {
        val root = UiNodeSnapshot(
            nodeId = 1,
            className = "android.widget.FrameLayout",
            children = listOf(
                UiNodeSnapshot(
                    nodeId = 2,
                    viewIdResourceName = "com.facebook.orca:id/toolbar",
                    className = "androidx.appcompat.widget.Toolbar",
                    children = listOf(
                        UiNodeSnapshot(
                            nodeId = 3,
                            viewIdResourceName = "com.facebook.orca:id/thread_title",
                            className = "android.widget.TextView",
                            text = "Ops Secondary",
                            bounds = NodeBounds(120, 40, 500, 120)
                        ),
                        UiNodeSnapshot(
                            nodeId = 4,
                            viewIdResourceName = "com.facebook.orca:id/video_call_btn",
                            className = "android.widget.ImageButton",
                            contentDescription = "Video call",
                            isClickable = true,
                            bounds = NodeBounds(800, 40, 920, 140)
                        ),
                        UiNodeSnapshot(
                            nodeId = 5,
                            viewIdResourceName = "com.facebook.orca:id/audio_call_btn",
                            className = "android.widget.ImageButton",
                            contentDescription = "Audio call",
                            isClickable = true,
                            bounds = NodeBounds(680, 40, 790, 140)
                        )
                    )
                )
            )
        )

        val audioBtn = MessengerNodeFinder.findAudioCallButton(root)
        assertNotNull(audioBtn)
        assertEquals(5, audioBtn?.nodeId)

        val header = MessengerNodeFinder.extractConversationHeader(root)
        assertEquals("Ops Secondary", header.primaryTitle)
    }

    @Test
    fun `strictly rejects video call button when no audio call button exists`() {
        val root = UiNodeSnapshot(
            nodeId = 1,
            children = listOf(
                UiNodeSnapshot(
                    nodeId = 2,
                    viewIdResourceName = "com.facebook.orca:id/video_call_action",
                    contentDescription = "Start a video call",
                    isClickable = true
                ),
                UiNodeSnapshot(
                    nodeId = 3,
                    viewIdResourceName = "com.facebook.orca:id/camera_button",
                    contentDescription = "Camera call",
                    isClickable = true
                )
            )
        )

        val audioBtn = MessengerNodeFinder.findAudioCallButton(root)
        assertNull(audioBtn)
    }

    @Test
    fun `strictly rejects cellular sms dialer whatsapp and telegram call nodes`() {
        val root = UiNodeSnapshot(
            nodeId = 1,
            children = listOf(
                UiNodeSnapshot(
                    nodeId = 2,
                    viewIdResourceName = "com.android.dialer:id/cellular_call",
                    contentDescription = "Cellular Call",
                    isClickable = true
                ),
                UiNodeSnapshot(
                    nodeId = 3,
                    viewIdResourceName = "com.android.mms:id/sms_call",
                    contentDescription = "SMS Audio Call",
                    isClickable = true
                ),
                UiNodeSnapshot(
                    nodeId = 4,
                    contentDescription = "WhatsApp voice call",
                    isClickable = true
                ),
                UiNodeSnapshot(
                    nodeId = 5,
                    contentDescription = "Telegram audio call",
                    isClickable = true
                )
            )
        )

        assertNull(MessengerNodeFinder.findAudioCallButton(root))
    }

    @Test
    fun `finds end call button reliably`() {
        val root = UiNodeSnapshot(
            nodeId = 1,
            children = listOf(
                UiNodeSnapshot(
                    nodeId = 2,
                    contentDescription = "Mute",
                    isClickable = true
                ),
                UiNodeSnapshot(
                    nodeId = 3,
                    viewIdResourceName = "com.facebook.orca:id/end_call_button",
                    contentDescription = "End call",
                    isClickable = true
                )
            )
        )

        val endBtn = MessengerNodeFinder.findEndCallButton(root)
        assertNotNull(endBtn)
        assertEquals(3, endBtn?.nodeId)
    }

    @Test
    fun `reports active call timer observed ONLY when both timer and active call control exist`() {
        val activeCallTree = UiNodeSnapshot(
            nodeId = 1,
            children = listOf(
                UiNodeSnapshot(nodeId = 2, text = "Ops Secondary"),
                UiNodeSnapshot(nodeId = 3, text = "00:14"),
                UiNodeSnapshot(nodeId = 4, contentDescription = "Mute", isClickable = true),
                UiNodeSnapshot(nodeId = 5, contentDescription = "End call", isClickable = true)
            )
        )

        val activeObs = MessengerNodeFinder.observeCallScreenState(activeCallTree)
        assertEquals(ObservedCallScreenState.ACTIVE_CALL_TIMER_OBSERVED, activeObs.state)
        assertEquals("00:14", activeObs.observedTimerText)

        // Timer text alone (e.g. a timestamp in chat) without call controls must NEVER claim active!
        val chatTimestampOnlyTree = UiNodeSnapshot(
            nodeId = 10,
            children = listOf(
                UiNodeSnapshot(nodeId = 11, text = "Ops Secondary"),
                UiNodeSnapshot(nodeId = 12, text = "01:25")
            )
        )
        val chatObs = MessengerNodeFinder.observeCallScreenState(chatTimestampOnlyTree)
        assertEquals(ObservedCallScreenState.UNKNOWN, chatObs.state)

        // Clock time with AM/PM alongside End Call must be RINGING_OBSERVED, not ACTIVE_CALL_TIMER_OBSERVED
        val ringingTree = UiNodeSnapshot(
            nodeId = 20,
            children = listOf(
                UiNodeSnapshot(nodeId = 21, text = "Calling..."),
                UiNodeSnapshot(nodeId = 22, text = "10:45 AM"),
                UiNodeSnapshot(nodeId = 23, contentDescription = "End call", isClickable = true)
            )
        )
        val ringingObs = MessengerNodeFinder.observeCallScreenState(ringingTree)
        assertEquals(ObservedCallScreenState.RINGING_OBSERVED, ringingObs.state)
        assertNull(ringingObs.observedTimerText)
    }

    @Test
    fun `finds search input and recipient result row without clicking edit box`() {
        val searchResultsTree = UiNodeSnapshot(
            nodeId = 1,
            children = listOf(
                UiNodeSnapshot(
                    nodeId = 2,
                    viewIdResourceName = "com.facebook.orca:id/search_input",
                    className = "android.widget.EditText",
                    text = "Backup Ops",
                    isClickable = true,
                    isEditable = true,
                    bounds = NodeBounds(40, 60, 900, 160)
                ),
                UiNodeSnapshot(
                    nodeId = 3,
                    className = "android.widget.LinearLayout",
                    isClickable = true,
                    children = listOf(
                        UiNodeSnapshot(
                            nodeId = 4,
                            className = "android.widget.TextView",
                            text = "Backup Ops"
                        ),
                        UiNodeSnapshot(
                            nodeId = 5,
                            className = "android.widget.TextView",
                            text = "@ops.secondary"
                        )
                    )
                )
            )
        )

        val input = MessengerNodeFinder.findSearchInput(searchResultsTree)
        assertEquals(2, input?.nodeId)

        val row = MessengerNodeFinder.findRecipientResultRow(
            root = searchResultsTree,
            targetName = "Backup Ops",
            secondaryLabel = "@ops.secondary"
        )
        assertNotNull(row)
        assertEquals(3, row?.nodeId)
    }
}
