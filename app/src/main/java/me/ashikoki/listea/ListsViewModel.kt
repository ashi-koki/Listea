package me.ashikoki.listea

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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
        dao.addItem(listId, clean, now())
    }

    fun renameItem(itemId: Long, title: String) = edit(title) { clean ->
        dao.updateItemTitle(itemId, clean)
    }

    fun setItemCompleted(item: ListItemEntity, completed: Boolean) = launchDb {
        dao.setItemCompleted(item.listId, item.id, completed, now())
    }

    fun deleteItem(item: ListItemEntity) = launchDb {
        dao.removeItem(item.listId, item.id, now())
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
