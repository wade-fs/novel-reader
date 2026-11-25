package com.novel.reader.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.novel.reader.R
import com.novel.reader.model.ChapterContent

class NovelContentAdapter(
    private val onItemClick: (uiPosition: Int) -> Unit,
    private val onPrevChapterClick: () -> Unit,
    private val onNextChapterClick: () -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        const val TYPE_TITLE = 0
        const val TYPE_PARAGRAPH = 1
        const val TYPE_NAV_FOOTER = 4
    }

    sealed class DisplayItem(val uiPosition: Int) {
        class TitleItem(uiPos: Int, val title: String) : DisplayItem(uiPos)
        class ParagraphItem(uiPos: Int, val text: String, val paragraphIndex: Int) : DisplayItem(uiPos)
        class NavFooterItem(val hasPrev: Boolean, val hasNext: Boolean) : DisplayItem(-1)
    }

    private val items = mutableListOf<DisplayItem>()
    private var activeUiPosition: Int = -1

    fun setContent(chapterContent: ChapterContent, hasPrev: Boolean = true, hasNext: Boolean = true) {
        items.clear()
        var uiPos = 0

        // 1. 章節標題
        items.add(DisplayItem.TitleItem(uiPos++, chapterContent.title))

        // 2. 正文段落（工作紀錄已於解析階段濾除，絕不顯示）
        chapterContent.bodyParagraphs.forEachIndexed { pIdx, paragraph ->
            items.add(DisplayItem.ParagraphItem(uiPos++, paragraph, pIdx))
        }

        // 3. 底部上一章/下一章快速換章卡片
        items.add(DisplayItem.NavFooterItem(hasPrev, hasNext))

        activeUiPosition = -1
        notifyDataSetChanged()
    }

    fun clearContent() {
        items.clear()
        activeUiPosition = -1
        notifyDataSetChanged()
    }

    fun setActiveUiPosition(position: Int) {
        val oldPos = activeUiPosition
        activeUiPosition = position

        val oldItemIndex = items.indexOfFirst { it.uiPosition == oldPos }
        if (oldItemIndex != -1) {
            notifyItemChanged(oldItemIndex)
        }

        val newItemIndex = items.indexOfFirst { it.uiPosition == position }
        if (newItemIndex != -1) {
            notifyItemChanged(newItemIndex)
        }
    }

    fun getItemIndexByUiPosition(uiPosition: Int): Int {
        return items.indexOfFirst { it.uiPosition == uiPosition }
    }

    override fun getItemCount(): Int = items.size

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is DisplayItem.TitleItem -> TYPE_TITLE
            is DisplayItem.ParagraphItem -> TYPE_PARAGRAPH
            is DisplayItem.NavFooterItem -> TYPE_NAV_FOOTER
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_TITLE -> {
                val view = inflater.inflate(R.layout.item_chapter_title, parent, false)
                TitleViewHolder(view)
            }
            TYPE_PARAGRAPH -> {
                val view = inflater.inflate(R.layout.item_paragraph, parent, false)
                ParagraphViewHolder(view)
            }
            TYPE_NAV_FOOTER -> {
                val view = inflater.inflate(R.layout.item_chapter_nav_footer, parent, false)
                NavFooterViewHolder(view)
            }
            else -> throw IllegalArgumentException("Unknown viewType: $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        val isSpeaking = (item.uiPosition == activeUiPosition)

        when (holder) {
            is TitleViewHolder -> {
                val titleItem = item as DisplayItem.TitleItem
                holder.tvTitle.text = titleItem.title
                holder.itemView.setOnClickListener { onItemClick(item.uiPosition) }
            }
            is ParagraphViewHolder -> {
                val paraItem = item as DisplayItem.ParagraphItem
                holder.tvText.text = "\u3000\u3000" + paraItem.text
                holder.indicator.visibility = if (isSpeaking) View.VISIBLE else View.INVISIBLE
                holder.container.setBackgroundColor(
                    if (isSpeaking) ContextCompat.getColor(holder.itemView.context, R.color.highlight_speaking)
                    else Color.TRANSPARENT
                )
                holder.itemView.setOnClickListener { onItemClick(item.uiPosition) }
            }
            is NavFooterViewHolder -> {
                val footerItem = item as DisplayItem.NavFooterItem
                holder.btnPrev.isEnabled = footerItem.hasPrev
                holder.btnNext.isEnabled = footerItem.hasNext
                holder.btnPrev.setOnClickListener { onPrevChapterClick() }
                holder.btnNext.setOnClickListener { onNextChapterClick() }
            }
        }
    }

    class TitleViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvTitle: TextView = itemView.findViewById(R.id.tvChapterTitle)
    }

    class ParagraphViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val container: View = itemView.findViewById(R.id.containerParagraph)
        val indicator: View = itemView.findViewById(R.id.indicatorSpeaking)
        val tvText: TextView = itemView.findViewById(R.id.tvParagraphText)
    }

    class NavFooterViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val btnPrev: Button = itemView.findViewById(R.id.btnFooterPrevChapter)
        val btnNext: Button = itemView.findViewById(R.id.btnFooterNextChapter)
    }
}
