package me.ashikoki.listea

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.ashikoki.listea.data.ListDetail
import me.ashikoki.listea.data.ListEntity
import me.ashikoki.listea.data.ListItemEntity
import me.ashikoki.listea.data.ListSummary
import me.ashikoki.listea.data.ListeaDatabase

/** Holds the List screens' state. Talks to the DAO directly; there is no repository layer yet. */
class ListsViewModel(application: Application) : AndroidViewModel(application) {

    private val dao = ListeaDatabase.get(application).listsDao()

    val summaries: StateFlow<List<ListSummary>> = dao.observeSummaries()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun observeDetail(listId: Long): Flow<ListDetail?> =
        combine(dao.observeList(listId), dao.observeItems(listId)) { list, items ->
            list?.let { ListDetail(it, items) }
        }

    fun createList(title: String) = edit(title) { clean ->
        dao.insertList(ListEntity(title = clean, createdAt = now()))
    }

    fun renameList(listId: Long, title: String) = edit(title) { clean ->
        dao.updateListTitle(listId, clean)
    }

    fun deleteList(listId: Long) = launchDb { dao.deleteList(listId) }

    fun addItem(listId: Long, title: String) = edit(title) { clean ->
        onCompletionTransition(listId, dao.addItem(listId, clean, now()))
    }

    fun renameItem(itemId: Long, title: String) = edit(title) { clean ->
        dao.updateItemTitle(itemId, clean)
    }

    fun setItemCompleted(item: ListItemEntity, completed: Boolean) = launchDb {
        onCompletionTransition(
            item.listId,
            dao.setItemCompleted(item.listId, item.id, completed, now())
        )
    }

    fun deleteItem(item: ListItemEntity) = launchDb {
        onCompletionTransition(item.listId, dao.removeItem(item.listId, item.id, now()))
    }

    fun setWebhookEnabled(listId: Long, enabled: Boolean) = launchDb {
        dao.setWebhookEnabled(listId, enabled)
    }

    fun setWebhookUrl(listId: Long, url: String) = launchDb {
        dao.setWebhookUrl(listId, url)
    }

    /** Explicit user action, so it sends regardless of the enabled toggle and never touches state. */
    fun testWebhook(listId: Long) = launchDb {
        deliver(listId, EVENT_WEBHOOK_TEST, requireEnabled = false)
    }

    /**
     * The single place the completion action fires. [transitioned] comes from the DAO transaction
     * that performed the edit, so a list that merely stays complete never delivers again.
     */
    private suspend fun onCompletionTransition(listId: Long, transitioned: Boolean) {
        if (transitioned) deliver(listId, EVENT_LIST_COMPLETED, requireEnabled = true)
    }

    private suspend fun deliver(listId: Long, event: String, requireEnabled: Boolean) {
        val list = dao.getList(listId) ?: return
        if (requireEnabled && !list.webhookEnabled) return

        val url = list.webhookUrl.trim()
        val urlProblem = webhookUrlError(url)
        if (urlProblem != null) {
            dao.recordDelivery(listId, now(), DeliveryStatus.FAILED.name, null, urlProblem)
            return
        }

        val payload = buildWebhookPayload(event, list, dao.getItems(listId))
        val result = withContext(Dispatchers.IO) { postWebhook(url, payload) }
        dao.recordDelivery(listId, now(), result.status.name, result.httpCode, result.error)
    }

    /** Runs [block] with a trimmed title, ignoring blank input. */
    private fun edit(title: String, block: suspend (String) -> Unit) {
        val clean = title.trim()
        if (clean.isEmpty()) return
        launchDb { block(clean) }
    }

    private fun launchDb(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    private fun now() = System.currentTimeMillis()
}
