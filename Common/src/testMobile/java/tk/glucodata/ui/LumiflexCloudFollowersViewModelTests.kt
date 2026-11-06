package tk.glucodata.ui

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.Dispatchers
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import tk.glucodata.R
import tk.glucodata.share.lumiflex.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = LumiflexCloudTestApplication::class)
class LumiflexCloudFollowersViewModelTests {
    private val stores = mutableListOf<ViewModelStore>()
    private val app get() = RuntimeEnvironment.getApplication()
    private val settings get() = LumiflexCloudSettings(app)
    private val row = FollowerInfo(userAuthorizationId = "a1", readerUserName = "79991234567",
        readerAlias = "Mum", dataProviderId = "u1", normalPushState = 1, emergePushState = 1)

    private class Transport : LumiflexCloudClient.Transport {
        var posts = 0
        var failPost = false
        var failList = false
        var onPost: () -> Unit = {}
        var lastBody = JSONObject()
        var listBody = """{"code":200,"data":[{"userAuthorizationId":"a1","dataProviderId":"u1","readerUserName":"79991234567","readerAlias":"Mum","normalPushState":1,"emergePushState":1}]}"""
        override fun post(url: String, body: String, headers: Map<String, String>): LumiflexCloudClient.HttpResult {
            posts++
            lastBody = JSONObject(body)
            onPost()
            return LumiflexCloudClient.HttpResult(200, if (failPost) """{"code":400}""" else """{"code":200,"data":{}}""")
        }
        override fun get(url: String, headers: Map<String, String>) = LumiflexCloudClient.HttpResult(200,
            if (failList) """{"code":400}""" else listBody)
    }

    @Before fun reset() { settings.clear(); settings.startSession("owner", "token", "u1") }
    @After fun clear() { stores.forEach { it.clear() } }

    private fun model(transport: Transport = Transport()): LumiflexCloudFollowersViewModel {
        val model = LumiflexCloudFollowersViewModel(app, LumiflexCloudFollowerManager(
            LumiflexCloudClient(transport, encrypt = { it }, decrypt = { it }), settings), Dispatchers.Unconfined)
        stores += ViewModelStore().also { it.put("followers", model) }
        ShadowLooper.idleMainLooper()
        return model
    }

    @Test fun failedSaveKeepsConfirmedRowAndDraftForRetry() {
        val transport = Transport().apply { failPost = true }
        val model = model(transport)
        model.edit(model.state.value.followers.single())
        val draft = row.copy(readerAlias = "Family", normalPushState = 0, extremeHighPushState = 1)
        model.editChanged(draft)
        assertEquals(row, model.state.value.followers.single())
        model.saveEditor()
        ShadowLooper.idleMainLooper()
        assertEquals(row, model.state.value.followers.single())
        assertEquals(draft, model.state.value.editor)
        assertEquals(R.string.savefailed, model.state.value.editorError)
        transport.failPost = false
        model.saveEditor()
        ShadowLooper.idleMainLooper()
        assertEquals(draft, model.state.value.editor)
        assertFalse(model.state.value.editorDirty)
        assertEquals(draft, model.state.value.followers.single())
        assertEquals(2, transport.posts)
    }

    @Test fun failedInviteRetainsFormattedAccountAndNameUntilAcknowledged() {
        val transport = Transport().apply { failPost = true }
        val model = model(transport)
        model.accountChanged("+7 (999) 123-45-67")
        model.aliasChanged("Family")
        model.invite()
        ShadowLooper.idleMainLooper()
        assertEquals("+7 (999) 123-45-67", model.state.value.account)
        assertEquals("Family", model.state.value.alias)
        assertEquals(R.string.lumiflex_status_invite_failed, model.state.value.inviteError)
        transport.failPost = false
        model.invite()
        ShadowLooper.idleMainLooper()
        assertEquals("", model.state.value.account)
        assertEquals("79991234567", transport.lastBody.getString("readerUserName"))
    }

    @Test fun duplicateSaveIsBlockedWhileOneRequestIsRunning() {
        val transport = Transport()
        val model = model(transport)
        model.edit(row)
        model.editChanged(row.copy(hideState = 1))
        transport.onPost = { model.saveEditor(); model.removeEditor(); model.dismissEditor() }
        model.saveEditor()
        ShadowLooper.idleMainLooper()
        assertEquals(1, transport.posts)
        assertEquals(1, model.state.value.followers.single().hideState)
        assertEquals(1, model.state.value.editor!!.hideState)
    }

    @Test fun failedRefreshPreservesTheLastConfirmedListAndCanRetry() {
        val transport = Transport()
        val model = model(transport)
        transport.failList = true
        model.refresh()
        ShadowLooper.idleMainLooper()
        assertTrue(model.state.value.loadFailed)
        assertEquals(listOf(row), model.state.value.followers)
        transport.failList = false
        model.refresh()
        ShadowLooper.idleMainLooper()
        assertFalse(model.state.value.loadFailed)
    }

    @Test fun removalDoesNotDisappearLocallyUntilServerConfirms() {
        val transport = Transport().apply { failPost = true }
        val model = model(transport)
        model.edit(row)
        model.removeEditor()
        ShadowLooper.idleMainLooper()
        assertEquals(listOf(row), model.state.value.followers)
        assertNotNull(model.state.value.editor)
        transport.failPost = false
        model.removeEditor()
        ShadowLooper.idleMainLooper()
        assertTrue(model.state.value.followers.isEmpty())
        assertNull(model.state.value.editor)
    }

    @Test fun inlineInvitationKeepsItsDraftWhileEditingAFollowerAndPreservesEmailPlusAlias() {
        val model = model()
        model.emailModeChanged(true)
        model.accountChanged("family+reader@example.org")
        model.aliasChanged("Family")
        model.edit(row)
        model.dismissEditor()
        assertTrue(model.state.value.canInvite)
        assertEquals("family+reader@example.org", model.state.value.account)
        assertEquals("Family", model.state.value.alias)
        model.emailModeChanged(false)
        assertFalse(model.state.value.canInvite)
        model.accountChanged("7999")
        assertFalse(model.state.value.canInvite)
    }

    @Test fun oneFollowerOpensByDefaultAndExplicitCollapseSurvivesRefresh() {
        val model = model()
        assertEquals(row, model.state.value.editor)
        model.dismissEditor()
        model.refresh()
        ShadowLooper.idleMainLooper()
        assertNull(model.state.value.editor)
    }

    @Test fun multipleFollowersCollapseAndTheRemainingSingleFollowerOpens() {
        val transport = Transport()
        val one = transport.listBody
        val model = model(transport)
        transport.listBody = """{"code":200,"data":[{"userAuthorizationId":"a1","dataProviderId":"u1","readerUserName":"79991234567"},{"userAuthorizationId":"a2","dataProviderId":"u1","readerUserName":"family@example.org"}]}"""
        model.refresh()
        ShadowLooper.idleMainLooper()
        assertEquals(2, model.state.value.followers.size)
        assertNull(model.state.value.editor)
        transport.listBody = one
        model.refresh()
        ShadowLooper.idleMainLooper()
        assertEquals(row, model.state.value.editor)
    }

    @Test fun refreshingTheListPreservesUnsavedEdits() {
        val model = model()
        val draft = row.copy(readerAlias = "Family", emergePushState = 0)
        model.editChanged(draft)
        model.refresh()
        ShadowLooper.idleMainLooper()
        assertEquals(draft, model.state.value.editor)
        assertEquals(row, model.state.value.followers.single())
    }
}
