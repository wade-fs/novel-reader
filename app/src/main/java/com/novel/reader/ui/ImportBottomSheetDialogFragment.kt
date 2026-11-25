package com.novel.reader.ui

import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.novel.reader.databinding.DialogImportGithubBinding
import com.novel.reader.util.GitHubHelper
import com.novel.reader.util.GitHubLoadResult
import com.novel.reader.util.WebBookImportResult
import com.novel.reader.util.WebNovelFetcher
import com.novel.reader.util.WebNovelParser
import kotlinx.coroutines.launch

class ImportBottomSheetDialogFragment(
    private val onImportSuccess: (result: GitHubLoadResult.Success) -> Unit,
    private val onWebBookImported: (result: WebBookImportResult.Success) -> Unit,
    private val onOpenWebBrowser: (url: String) -> Unit,
    private val onPickArchive: () -> Unit,
    private val onPickDirectory: () -> Unit
) : BottomSheetDialogFragment() {

    constructor() : this({}, {}, {}, {}, {})

    private var _binding: DialogImportGithubBinding? = null
    private val binding get() = _binding!!

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
        _binding = DialogImportGithubBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 貼上剪貼簿
        binding.btnPasteClipboard.setOnClickListener {
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboard.primaryClip?.getItemAt(0)?.text?.toString()?.trim()
            if (!clip.isNullOrEmpty()) {
                binding.etGithubUrl.setText(clip)
            } else {
                Toast.makeText(requireContext(), "剪貼簿為空", Toast.LENGTH_SHORT).show()
            }
        }

        // 範例捷徑（網路小說 + GitHub）
        binding.chipExamplePiaotia.setOnClickListener {
            binding.etGithubUrl.setText("https://www.piaotia.com/html/3/3759/")
        }
        binding.chipExampleCzbooks.setOnClickListener {
            binding.etGithubUrl.setText("https://czbooks.net/n/ucpije")
        }
        binding.chipExampleUukanshu.setOnClickListener {
            binding.etGithubUrl.setText("https://uukanshu.cc/book/27525/")
        }
        binding.chipExampleYuanTree.setOnClickListener {
            binding.etGithubUrl.setText("https://github.com/wade-fs/novel/tree/main/yuan")
        }
        binding.chipExampleGuixu.setOnClickListener {
            binding.etGithubUrl.setText("https://github.com/wade-fs/novel/tree/main/song")
        }
        binding.chipExampleFormosa.setOnClickListener {
            binding.etGithubUrl.setText("https://github.com/wade-fs/novel/tree/main/sex")
        }

        // 抓取並開始朗讀（自動分辨 GitHub 或一般小說網站）
        binding.btnDownloadAndRead.setOnClickListener {
            val url = binding.etGithubUrl.text?.toString()?.trim() ?: ""
            if (url.isEmpty()) {
                Toast.makeText(requireContext(), "請輸入或貼上小說網址", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val ghInfo = GitHubHelper.parseUrl(url)
            if (ghInfo != null) {
                startGitHubDownload(ghInfo)
                return@setOnClickListener
            }

            if (WebNovelParser.isWebNovelUrl(url)) {
                startWebNovelImport(url)
                return@setOnClickListener
            }

            Toast.makeText(requireContext(), "請輸入有效的 http:// 或 https:// 網址", Toast.LENGTH_LONG).show()
        }

        // 直接使用內建防廣告瀏覽器開啟
        binding.btnOpenWebBrowser.setOnClickListener {
            val url = binding.etGithubUrl.text?.toString()?.trim() ?: ""
            if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
                Toast.makeText(requireContext(), "請先輸入或貼上小說網站網址", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            dismiss()
            onOpenWebBrowser(url)
        }

        // 本機上傳 ZIP / TAR
        binding.btnUploadArchive.setOnClickListener {
            dismiss()
            onPickArchive()
        }

        // 本機選取目錄
        binding.btnSelectDirectory.setOnClickListener {
            dismiss()
            onPickDirectory()
        }
    }

    private fun startWebNovelImport(url: String) {
        binding.layoutProgress.visibility = View.VISIBLE
        binding.btnDownloadAndRead.isEnabled = false
        binding.btnOpenWebBrowser.isEnabled = false
        isCancelable = false
        binding.tvProgressMessage.text = "正在連線解析小說網頁與章節目錄..."

        lifecycleScope.launch {
            val result = WebNovelFetcher.importFromUrlDirect(requireContext(), url)

            val b = _binding ?: return@launch
            b.layoutProgress.visibility = View.GONE
            b.btnDownloadAndRead.isEnabled = true
            b.btnOpenWebBrowser.isEnabled = true
            isCancelable = true

            when (result) {
                is WebBookImportResult.Success -> {
                    Toast.makeText(
                        requireContext(),
                        "已加入書單「${result.title}」（共 ${result.chapters.size} 章）",
                        Toast.LENGTH_SHORT
                    ).show()
                    dismiss()
                    onWebBookImported(result)
                }
                is WebBookImportResult.NeedsInteractiveWebView -> {
                    Toast.makeText(requireContext(), result.reason, Toast.LENGTH_SHORT).show()
                    dismiss()
                    onOpenWebBrowser(result.url)
                }
                is WebBookImportResult.Error -> {
                    Toast.makeText(requireContext(), "${result.message}，您也可以嘗試用內建防廣告瀏覽器開啟", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun startGitHubDownload(info: com.novel.reader.util.GitHubUrlInfo) {
        binding.layoutProgress.visibility = View.VISIBLE
        binding.btnDownloadAndRead.isEnabled = false
        binding.btnOpenWebBrowser.isEnabled = false
        isCancelable = false

        lifecycleScope.launch {
            val result = if (info.isSingleFile) {
                binding.tvProgressMessage.text = "正在下載單一章節 (${info.path.substringAfterLast("/")})..."
                GitHubHelper.downloadSingleChapter(requireContext(), info)
            } else {
                binding.tvProgressMessage.text = "正在從 GitHub 取得專案並解壓小說..."
                GitHubHelper.downloadTreeFolder(requireContext(), info) { msg ->
                    lifecycleScope.launch {
                        _binding?.tvProgressMessage?.text = msg
                    }
                }
            }

            val b = _binding ?: return@launch
            b.layoutProgress.visibility = View.GONE
            b.btnDownloadAndRead.isEnabled = true
            b.btnOpenWebBrowser.isEnabled = true
            isCancelable = true

            when (result) {
                is GitHubLoadResult.Success -> {
                    Toast.makeText(
                        requireContext(),
                        if (result.isSingleChapter) "已成功載入指定章節！" else "已成功載入小說：共 ${result.chapters.size} 章",
                        Toast.LENGTH_SHORT
                    ).show()
                    dismiss()
                    onImportSuccess(result)
                }
                is GitHubLoadResult.Error -> {
                    Toast.makeText(requireContext(), result.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
