package com.novel.reader.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.novel.reader.R
import com.novel.reader.model.BookEntry

class BookListAdapter(
    private var books: List<BookEntry>,
    private var currentBookId: String?,
    private val onSelectBook: (BookEntry) -> Unit,
    private val onDeleteBook: (BookEntry) -> Unit
) : RecyclerView.Adapter<BookListAdapter.ViewHolder>() {

    fun submitList(newBooks: List<BookEntry>, activeBookId: String?) {
        this.books = newBooks
        this.currentBookId = activeBookId
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = books.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_book_entry, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val book = books[position]
        val isCurrent = (book.id == currentBookId)
        val ctx = holder.itemView.context

        holder.tvTitle.text = book.title
        val chapterText = if (book.chapterCount > 0) "共 ${book.chapterCount} 章" else "點擊載入"
        holder.tvMeta.text = "${book.sourceType.displayLabel} · $chapterText"
        holder.tvCurrentBadge.visibility = if (isCurrent) View.VISIBLE else View.GONE

        holder.card.strokeColor = ContextCompat.getColor(
            ctx,
            if (isCurrent) R.color.primary else R.color.divider
        )
        holder.card.setCardBackgroundColor(
            ContextCompat.getColor(
                ctx,
                if (isCurrent) R.color.primary_container else R.color.bg_paper
            )
        )

        holder.itemView.setOnClickListener {
            onSelectBook(book)
        }
        holder.btnDelete.setOnClickListener {
            onDeleteBook(book)
        }
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val card: MaterialCardView = itemView.findViewById(R.id.cardBookItem)
        val tvTitle: TextView = itemView.findViewById(R.id.tvBookTitle)
        val tvMeta: TextView = itemView.findViewById(R.id.tvBookMeta)
        val tvCurrentBadge: TextView = itemView.findViewById(R.id.tvCurrentBadge)
        val btnDelete: MaterialButton = itemView.findViewById(R.id.btnDeleteBook)
    }
}
