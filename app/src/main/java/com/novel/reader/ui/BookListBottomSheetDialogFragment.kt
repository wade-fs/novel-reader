package com.novel.reader.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.novel.reader.databinding.BottomSheetBookListBinding
import com.novel.reader.model.BookEntry

class BookListBottomSheetDialogFragment(
    private val getBooks: () -> List<BookEntry>,
    private val getCurrentBookId: () -> String?,
    private val onSelectBook: (BookEntry) -> Unit,
    private val onDeleteBook: (BookEntry) -> Unit,
    private val onAddBook: () -> Unit
) : BottomSheetDialogFragment() {

    constructor() : this({ emptyList() }, { null }, {}, {}, {})

    private var _binding: BottomSheetBookListBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: BookListAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            dismissAllowingStateLoss()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetBookListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = BookListAdapter(
            books = emptyList(),
            currentBookId = getCurrentBookId(),
            onSelectBook = { book ->
                dismiss()
                onSelectBook(book)
            },
            onShowBookInfo = { book ->
                showBookInfoDialog(book)
            },
            onDeleteBook = { book ->
                confirmDelete(book)
            }
        )

        binding.rvBooks.layoutManager = LinearLayoutManager(requireContext())
        binding.rvBooks.adapter = adapter

        binding.btnAddBook.setOnClickListener {
            dismiss()
            onAddBook()
        }

        refreshList()
    }

    private fun showBookInfoDialog(book: BookEntry) {
        val infoText = buildString {
            if (book.subtitle.isNotBlank()) appendLine("副標題：${book.subtitle}")
            if (book.author.isNotBlank()) appendLine("作者：${book.author}")
            if (book.series.isNotBlank()) appendLine("系列 / 分類：${book.series}")
            if (book.era.isNotBlank()) appendLine("時代 / 狀態：${book.era}")
            appendLine("章節數：共 ${book.chapterCount} 章")
            appendLine("來源：${book.sourceType.displayLabel}")
            if (!book.sourceUriOrUrl.isNullOrBlank()) {
                appendLine("位址：${book.sourceUriOrUrl}")
            }
            if (book.tags.isNotEmpty()) {
                appendLine("標籤：${book.tags.joinToString("、")}")
            }
            if (book.description.isNotBlank()) {
                appendLine()
                appendLine("簡介：")
                append(book.description)
            }
        }.trim()

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(book.title)
            .setMessage(infoText)
            .setPositiveButton("開始閱讀") { _, _ ->
                dismiss()
                onSelectBook(book)
            }
            .setNeutralButton("刪除") { _, _ ->
                confirmDelete(book)
            }
            .setNegativeButton("關閉", null)
            .show()
    }

    private fun confirmDelete(book: BookEntry) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("刪除書單項目")
            .setMessage("確定要從書單刪除「${book.title}」嗎？")
            .setPositiveButton("刪除") { _, _ ->
                onDeleteBook(book)
                refreshList()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun refreshList() {
        val books = getBooks()
        binding.tvBookCount.text = "共 ${books.size} 本小說"
        if (books.isEmpty()) {
            binding.tvEmptyBooks.visibility = View.VISIBLE
            binding.rvBooks.visibility = View.GONE
        } else {
            binding.tvEmptyBooks.visibility = View.GONE
            binding.rvBooks.visibility = View.VISIBLE
        }
        adapter.submitList(books, getCurrentBookId())
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
