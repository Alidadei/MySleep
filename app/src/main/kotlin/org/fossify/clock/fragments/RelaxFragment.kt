package org.fossify.clock.fragments

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import org.fossify.clock.R
import org.fossify.clock.activities.NightTalkActivity
import org.fossify.clock.activities.SleepReportActivity
import org.fossify.clock.databinding.FragmentRelaxBinding
import org.fossify.clock.extensions.dbHelper
import org.fossify.clock.extensions.requiredActivity
import org.fossify.clock.helpers.AdGuard
import org.fossify.clock.helpers.CommunityPick
import org.fossify.clock.helpers.CommunityRemoteStore
import org.fossify.clock.helpers.InsomniaTypes
import org.fossify.clock.helpers.NightTalk
import org.fossify.clock.helpers.PicksRepository
import org.fossify.clock.helpers.RelaxDataIO
import org.fossify.clock.helpers.RelaxItem
import org.fossify.clock.helpers.RelaxStore
import org.fossify.clock.helpers.TimeTheme
import org.fossify.commons.extensions.applyColorFilter
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.beGoneIf
import org.fossify.commons.extensions.beVisible
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.ensureBackgroundThread
import kotlin.math.roundToInt

/**
 * The bedtime tab: brand header over the shared page sky, then entry cards
 * (favorites, curated picks, sleep report, night talk). Picks expand into a
 * ranked community list backed by Supabase; favorites expand into the local
 * list. Visual system mirrors the website: time-interpolated twin palettes
 * (lotus-purple night / amber day), Ma Shan Zheng display type, frosted cards.
 */
class RelaxFragment : Fragment(), TimeThemeAware {

    private enum class Section { FAVORITES, PICKS }

    companion object {
        /** 网站同款 DEAD_THRESHOLD：≥2 人举报同一链接即判失效 */
        private const val DEAD_THRESHOLD = 2
    }

    private lateinit var binding: FragmentRelaxBinding
    private var currentSection = Section.FAVORITES
    private var selectedType = InsomniaTypes.KEY_ALL
    private var chipsBuilt = false
    private var theme = TimeTheme.current()
    private var handTf: Typeface? = null

    /** 链接失效举报缓存（网站同款语义：≥2 人举报置灰+多人报告徽标；拉取失败保留旧值） */
    private val deadReports = mutableListOf<CommunityRemoteStore.DeadReport>()

    private var ioKind = RelaxDataIO.KIND_FAVORITES

    private val filePicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                handlePickedLocalFile(uri)
            }
        }

    private val dataImporter =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                handleDataImport(uri)
            }
        }

    private val dataExporter =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) {
                handleDataExport(uri)
            }
        }

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            showHub()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        binding = FragmentRelaxBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)

        applyTimeTheme(TimeTheme.current())

        binding.cardFavorites.setOnClickListener { showSection(Section.FAVORITES) }
        binding.cardPicks.setOnClickListener { showSection(Section.PICKS) }
        binding.cardReport.setOnClickListener {
            startActivity(Intent(requireContext(), SleepReportActivity::class.java))
        }
        binding.cardNightTalk.setOnClickListener {
            startActivity(Intent(requireContext(), NightTalkActivity::class.java))
        }

        binding.relaxBack.setOnClickListener { showHub() }
        binding.relaxProfileEntry.setOnClickListener {
            org.fossify.clock.helpers.ProfileDialogs.show(requireActivity())
        }
        binding.relaxAddFavorite.setOnClickListener { showAddChoiceDialog() }
        binding.relaxRecommend.setOnClickListener { showRecommendDialog() }
        binding.relaxImportData.setOnClickListener {
            ioKind = if (currentSection == Section.FAVORITES) {
                RelaxDataIO.KIND_FAVORITES
            } else {
                RelaxDataIO.KIND_COMMUNITY
            }
            dataImporter.launch(arrayOf("application/json", "text/*", "*/*"))
        }
        binding.relaxExportData.setOnClickListener {
            ioKind = if (currentSection == Section.FAVORITES) {
                RelaxDataIO.KIND_FAVORITES
            } else {
                RelaxDataIO.KIND_COMMUNITY
            }
            dataExporter.launch(RelaxDataIO.defaultFileName(ioKind))
        }

        showHub()
        refreshReportSubtitle()
    }

    override fun onResume() {
        super.onResume()
        applyTimeTheme(TimeTheme.current())
        refreshReportSubtitle()
        if (binding.relaxSection.visibility == View.VISIBLE) {
            populateSection(currentSection, fromCloud = true)
        }
    }

    // ---- 时辰主题（复刻网站：夜藕荷紫 × 昼琥珀棕，5–8/17–20 插值） ----

    override fun applyTimeTheme(theme: TimeTheme) {
        this.theme = theme
        if (!isAdded) return

        // 品牌区（月/日随 t 交叉淡化，accent 描边；标题 grey 手写体；渐变短线 accent→accent2）
        binding.brandMoon.alpha = 1f - theme.t
        binding.brandSun.alpha = theme.t
        binding.brandMoon.applyColorFilter(theme.accent)
        binding.brandSun.applyColorFilter(theme.accent)
        binding.brandTitle.setTextColor(theme.grey)
        binding.brandTitle.typeface = hand()
        binding.brandLine.background = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(theme.accent, theme.accent2)
        ).apply { cornerRadius = 6f }
        binding.brandSubtitle.setTextColor(theme.sub)
        binding.brandSubtitle.typeface = hand()

        // hub 卡片流：动态卡片底 + accent 图标 + ink 标题 + sub 副标题
        listOf(
            binding.cardFavorites to binding.cardFavoritesIcon,
            binding.cardPicks to binding.cardPicksIcon,
            binding.cardReport to binding.cardReportIcon,
            binding.cardNightTalk to binding.cardNightTalkIcon
        ).forEach { (card, icon) ->
            card.background = translucentCardDrawable()
            icon.applyColorFilter(theme.accent)
            var titleSeen = false
            for (i in 0 until card.childCount) {
                val inner = card.getChildAt(i)
                if (inner is ViewGroup) {
                    for (j in 0 until inner.childCount) {
                        val tv = inner.getChildAt(j) as? org.fossify.commons.views.MyTextView
                        if (tv != null) {
                            if (!titleSeen) {
                                tv.setTextColor(theme.ink)
                                titleSeen = true
                            } else {
                                tv.setTextColor(theme.sub)
                            }
                        }
                    }
                }
            }
        }

        // section 固定件
        binding.relaxBack.setTextColor(theme.accent)
        binding.relaxSectionTitle.setTextColor(theme.ink)
        binding.relaxImportData.setTextColor(theme.sub)
        binding.relaxExportData.setTextColor(theme.sub)
        binding.relaxEmptyCustom.setTextColor(theme.sub)
        binding.relaxProfileEntry.setTextColor(theme.accent)

        // Material 按钮对齐暮紫（可交互=暮紫）
        val actTint = android.content.res.ColorStateList.valueOf(theme.act)
        binding.relaxRecommend.backgroundTintList = actTint
        binding.relaxAddFavorite.backgroundTintList = actTint

        if (binding.relaxSection.visibility == View.VISIBLE && currentSection == Section.PICKS) {
            populateSection(Section.PICKS, fromCloud = true)
        }
    }

    /** 手写体（网站 Ma Shan Zheng；失败回退系统衬线楷体感） */
    private fun hand(): Typeface {
        if (handTf == null) {
            handTf = try {
                Typeface.createFromAsset(requireContext().assets, "fonts/ma_shan_zheng.ttf")
            } catch (e: Exception) {
                Typeface.SERIF
            }
        }
        return handTf!!
    }

    private fun cardDrawable(): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(theme.card)
            setStroke(
                1,
                android.graphics.Color.argb(31, // accent 12%
                    (theme.accent shr 16) and 0xFF,
                    (theme.accent shr 8) and 0xFF,
                    theme.accent and 0xFF
                )
            )
            cornerRadius = 14f * resources.displayMetrics.density
        }

    /** 网站卡片是毛玻璃（card 40% + blur），Android 用 60% 半透明实底近似降级 */
    private fun translucentCardDrawable(): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(android.graphics.Color.argb(153,
                (theme.card shr 16) and 0xFF,
                (theme.card shr 8) and 0xFF,
                theme.card and 0xFF))
            setStroke(
                1,
                android.graphics.Color.argb(31,
                    (theme.accent shr 16) and 0xFF,
                    (theme.accent shr 8) and 0xFF,
                    theme.accent and 0xFF)
            )
            cornerRadius = 14f * resources.displayMetrics.density
        }

    // ---- hub / section 切换 ----

    private fun showHub() {
        backCallback.isEnabled = false
        binding.relaxHub.beVisible()
        binding.relaxSection.beGone()
    }

    private fun showSection(section: Section) {
        currentSection = section
        backCallback.isEnabled = true
        binding.relaxHub.beGone()
        binding.relaxSection.beVisible()

        binding.relaxSectionTitle.setText(
            if (section == Section.FAVORITES) R.string.relax_custom_label else R.string.relax_picks_label
        )
        // 站主定稿：精选页顶部不再重复"精选推荐"字样
        binding.relaxSectionTitle.beGoneIf(section == Section.PICKS)
        binding.relaxTypeChipsScroll.beVisibleIf(section == Section.PICKS)
        binding.relaxRecommend.beVisibleIf(section == Section.PICKS)
        if (section == Section.PICKS && !chipsBuilt) {
            buildTypeChips()
        }
        populateSection(section)
    }

    private fun populateSection(section: Section, fromCloud: Boolean = false) {
        binding.relaxHolder.removeAllViews()

        if (section == Section.PICKS) {
            PicksRepository.getPicks(requireContext())
                .filter { selectedType == InsomniaTypes.KEY_ALL || it.type == selectedType }
                .forEach { item ->
                    addItemRow(item, deletable = false, favoriteOnLongPress = true)
                }
            addSectionLabel(getString(R.string.community_label))
            val communityPicks = RelaxStore.getCommunityPicks(requireContext())
                .filter { selectedType == InsomniaTypes.KEY_ALL || it.type == selectedType }
                .sortedWith(
                    compareByDescending<CommunityPick> { it.ratings?.average() ?: 0.0 }
                        .thenByDescending { it.ratings?.size ?: 0 }
                        .thenByDescending { it.addedAt }
                )
            communityPicks.forEachIndexed { index, pick ->
                addCommunityRow(pick, rank = index + 1)
            }
            binding.relaxEmptyCustom.beGone()
            binding.relaxAddFavorite.beGone()
            binding.relaxRecommend.beVisible()
            binding.relaxListScroll.scrollTo(0, 0)
            if (!fromCloud) {
                refreshCommunityFromCloud()
            }
            return
        }

        val customItems = RelaxStore.getCustomItems(requireContext())
        customItems.forEach { item ->
            addItemRow(item, deletable = true)
        }
        binding.relaxEmptyCustom.beVisibleIf(customItems.isEmpty())
        binding.relaxAddFavorite.beVisible()
        binding.relaxRecommend.beGone()
    }

    /** Cloud refresh: fetch authoritative community picks + dead-link reports
     *  from Supabase, replace the local cache and re-render. Silent on failure
     *  (offline keeps serving the cache and the previous report marks). */
    private fun refreshCommunityFromCloud() {
        ensureBackgroundThread {
            val remote = CommunityRemoteStore.load()
            if (remote != null && remote.isNotEmpty()) {
                RelaxStore.replaceAllCommunityPicks(requireContext(), remote)
            }
            val reports = CommunityRemoteStore.fetchDeadReports()
            activity?.runOnUiThread {
                if (reports != null) {
                    deadReports.clear()
                    deadReports.addAll(reports)
                }
                if (isAdded && currentSection == Section.PICKS &&
                    (remote != null || reports != null)
                ) {
                    populateSection(Section.PICKS, fromCloud = true)
                }
            }
        }
    }

    /** 网站同款 deadInfo：该链接的举报数 + 我是否已举报（uid 来自画像匿名标识） */
    private fun deadInfo(pickId: Long): Pair<Int, Boolean> {
        val uid = NightTalk.getUid(requireContext())
        var n = 0
        var me = false
        for (r in deadReports) {
            if (r.pickId == pickId.toString()) {
                n++
                if (r.uid == uid) me = true
            }
        }
        return n to me
    }

    private fun refreshReportSubtitle() {
        ensureBackgroundThread {
            val records = try {
                requiredActivity.dbHelper.getRecentSleepRecords(30)
            } catch (e: Exception) {
                return@ensureBackgroundThread
            }
            activity?.runOnUiThread {
                if (!isAdded) {
                    return@runOnUiThread
                }

                binding.reportSubtitle.text = if (records.isEmpty()) {
                    getString(R.string.report_subtitle_empty)
                } else {
                    val oversleepMinutes = records.map {
                        ((it.stoppedAtMillis - it.ringAtMillis) / 60000L).coerceAtLeast(0L)
                    }
                    val avgOversleep =
                        (oversleepMinutes.sum().toFloat() / oversleepMinutes.size).roundToInt()
                    val onTimePercent =
                        (oversleepMinutes.count { it <= 5 } * 100f / records.size).roundToInt()
                    getString(
                        R.string.sleep_report_summary_fmt,
                        records.size,
                        onTimePercent,
                        avgOversleep
                    )
                }
            }
        }
    }

    private fun addSectionLabel(text: String) {
        val label = LayoutInflater.from(requireContext()).inflate(
            R.layout.item_relax_section, binding.relaxHolder, false
        ) as org.fossify.commons.views.MyTextView
        label.text = text
        label.setTextColor(theme.sub)
        binding.relaxHolder.addView(label)
    }

    /** 书签按钮：精选/社区条目一键收藏（已收藏金色实心），点击切换 */
    private fun bindBookmarkButton(row: LinearLayout, url: String) {
        val favButton = row.findViewById<android.widget.ImageView>(R.id.relax_item_fav)
        favButton.background.setTint(theme.line)

        fun refresh() {
            val favorited = RelaxStore.isUrlFavorited(requireContext(), url)
            favButton.setImageResource(
                if (favorited) R.drawable.ic_bookmark_filled_vector
                else R.drawable.ic_bookmark_vector
            )
            favButton.applyColorFilter(if (favorited) theme.accent else theme.sub)
        }
        refresh()

        favButton.setOnClickListener {
            if (RelaxStore.isUrlFavorited(requireContext(), url)) {
                RelaxStore.getCustomItems(requireContext())
                    .filter { RelaxStore.urlKey(it.url) == RelaxStore.urlKey(url) }
                    .forEach { RelaxStore.removeCustomItem(requireContext(), it.id) }
                requireContext().toast(R.string.relax_favorite_removed)
            } else {
                val title = row.findViewById<org.fossify.commons.views.MyTextView>(R.id.relax_item_title)
                    .text
                    .removePrefix(getString(R.string.relax_sample_badge))
                    .toString()
                RelaxStore.addCustomItem(requireContext(), title, url)
                requireContext().toast(R.string.relax_favorite_done)
            }
            refresh()
        }
    }

    private fun addItemRow(item: RelaxItem, deletable: Boolean, favoriteOnLongPress: Boolean = false) {
        val row = LayoutInflater.from(requireContext()).inflate(
            R.layout.item_relax, binding.relaxHolder, false
        ) as LinearLayout
        row.background = translucentCardDrawable()

        val titleView = row.findViewById<org.fossify.commons.views.MyTextView>(R.id.relax_item_title)
        titleView.text = if (item.sample) {
            getString(R.string.relax_sample_badge) + item.title
        } else {
            item.title
        }
        titleView.setTextColor(theme.ink)
        row.findViewById<org.fossify.commons.views.MyTextView>(R.id.relax_item_url)
            .apply {
                text = when {
                    // placeholder rating shown until real user ratings land (contract §1)
                    item.sampleRatingAvg != null && item.sampleRatingCount != null ->
                        getString(
                            R.string.community_rating_fmt,
                            item.sampleRatingAvg,
                            item.sampleRatingCount
                        )

                    item.isLocal -> getString(R.string.relax_local_label)
                    else -> item.url
                }
                setTextColor(theme.sub)
            }
        val favBtn = row.findViewById<android.widget.ImageView>(R.id.relax_item_fav)
        favBtn.visibility = if (favoriteOnLongPress) View.VISIBLE else View.GONE

        row.setOnClickListener {
            openItem(item)
        }

        if (favoriteOnLongPress) {
            bindBookmarkButton(row, item.url)
            row.setOnLongClickListener {
                favoriteFromPick(item.title, item.url)
                true
            }
        }

        if (deletable) {
            row.setOnLongClickListener {
                showItemActionsDialog(item)
                true
            }
        }

        binding.relaxHolder.addView(row)
    }

    /** 社区榜单卡：rank mono 序号（第 1 名金灯点亮）+ ghost rate/fav 按钮 +
     *  行内五星评分（复刻网站 .card 全形态） */
    private fun addCommunityRow(pick: CommunityPick, rank: Int) {
        val row = LayoutInflater.from(requireContext()).inflate(
            R.layout.item_relax, binding.relaxHolder, false
        ) as LinearLayout
        row.background = translucentCardDrawable()

        row.findViewById<org.fossify.commons.views.MyTextView>(R.id.relax_item_title)
            .apply {
                text = pick.title
                setTextColor(theme.ink)
            }

        row.findViewById<org.fossify.commons.views.MyTextView>(R.id.relax_item_rank)
            .apply {
                visibility = View.VISIBLE
                text = "%02d".format(rank)
                setTextColor(if (rank == 1) theme.grey else theme.sub)
            }

        val ratings = pick.ratings
        val typeLabel = InsomniaTypes.labelKey(requireContext(), pick.type)
        val typePrefix = typeLabel.ifEmpty { "" }
        // 站主要求：推荐次数始终展示
        val recText = " · ${pick.recommendCount ?: 1} 人推荐"
        val metaText = when {
            !ratings.isNullOrEmpty() -> {
                val ratingText = getString(
                    R.string.community_rating_fmt, ratings.average(), ratings.size
                )
                (if (typePrefix.isEmpty()) "" else "$typePrefix · ") + ratingText + recText
            }

            typePrefix.isNotEmpty() -> "$typePrefix · ${pick.url}$recText"

            else -> pick.url + recText
        }
        val urlView = row.findViewById<org.fossify.commons.views.MyTextView>(R.id.relax_item_url)
        urlView.setTextColor(theme.sub)
        // 网站同款失效判定：≥2 人举报 → 卡片置灰 + 红色「多人报告」徽标（dead-tag）
        val (reportCount, _) = deadInfo(pick.id)
        if (reportCount >= DEAD_THRESHOLD) {
            row.alpha = 0.6f
            val badge = "  " + getString(R.string.dead_badge)
            urlView.text = android.text.SpannableString(metaText + badge).apply {
                setSpan(
                    android.text.style.ForegroundColorSpan(theme.bad),
                    metaText.length, metaText.length + badge.length,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        } else {
            urlView.text = metaText
        }

        // ghost rate 按钮：展开行内五星（网站 .card .rate + .stars）
        val rateBtn = row.findViewById<org.fossify.commons.views.MyTextView>(R.id.relax_item_rate)
        rateBtn.visibility = View.VISIBLE
        rateBtn.setTextColor(theme.grey)
        rateBtn.background.setTint(theme.line)
        val starsRow = row.findViewById<LinearLayout>(R.id.relax_item_stars)
        rateBtn.setOnClickListener {
            if (starsRow.childCount == 0) {
                buildStarsRow(starsRow, pick)
            }
            starsRow.visibility =
                if (starsRow.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        // ⚠ 链接失效举报（网站 .card .dead；一人一票，服务端 (pick_id,uid) 唯一去重）
        val deadBtn = row.findViewById<org.fossify.commons.views.MyTextView>(R.id.relax_item_dead)
        deadBtn.visibility = View.VISIBLE
        fun refreshDead() {
            val (_, me) = deadInfo(pick.id)
            val color = if (me) theme.bad else theme.grey
            deadBtn.setTextColor(color)
            deadBtn.background.setTint(if (me) theme.bad else theme.line)
        }
        refreshDead()
        deadBtn.setOnClickListener {
            val (_, me) = deadInfo(pick.id)
            if (me) return@setOnClickListener
            // 先弹说明浮窗，确认后才提交（避免误触直接上报）
            requireActivity().getAlertDialogBuilder()
                .setTitle(R.string.dead_report_title)
                .setMessage(R.string.dead_report_message)
                .setPositiveButton(R.string.dead_report_confirm) { _, _ ->
                    deadBtn.isEnabled = false
                    ensureBackgroundThread {
                        val uid = NightTalk.getUid(requireContext())
                        val ok = CommunityRemoteStore.reportDead(pick.id, uid)
                        activity?.runOnUiThread {
                            if (!isAdded) return@runOnUiThread
                            deadBtn.isEnabled = true
                            if (ok) {
                                deadReports.add(
                                    CommunityRemoteStore.DeadReport(pick.id.toString(), uid)
                                )
                                requireContext().toast(R.string.dead_reported)
                            } else {
                                requireContext().toast(R.string.dead_report_fail)
                            }
                            refreshDead()
                        }
                    }
                }
                .setNegativeButton(org.fossify.commons.R.string.cancel, null)
                .show()
        }

        row.setOnClickListener { openItem(RelaxItem(pick.id, pick.title, pick.url)) }
        bindBookmarkButton(row, pick.url)
        row.setOnLongClickListener {
            favoriteFromPick(pick.title, pick.url)
            true
        }

        binding.relaxHolder.addView(row)
    }

    /** 五星行内评分：空心 accent，点击即提交，✓ 反馈 1.5s（网站 .stars 语义） */
    private fun buildStarsRow(starsRow: LinearLayout, pick: CommunityPick) {
        starsRow.removeAllViews()
        for (i in 1..5) {
            val star = LayoutInflater.from(requireContext())
                .inflate(R.layout.item_star, starsRow, false) as org.fossify.commons.views.MyTextView
            star.text = "★"
            star.setTextColor(theme.accent)
            star.setOnClickListener {
                starsRow.removeAllViews()
                val done = LayoutInflater.from(requireContext()).inflate(
                    R.layout.item_star, starsRow, false
                ) as org.fossify.commons.views.MyTextView
                done.text = "✓ 感谢你的评价"
                done.setTextColor(theme.ok)
                done.background = null
                starsRow.addView(done)
                ensureBackgroundThread {
                    val ok = CommunityRemoteStore.rate(pick.id, i)
                    activity?.runOnUiThread {
                        if (!isAdded) {
                            return@runOnUiThread
                        }
                        if (ok) {
                            RelaxStore.rateCommunityPick(requireContext(), pick.id, i)
                        } else {
                            requireContext().toast(R.string.community_cloud_failed)
                        }
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            if (isAdded && currentSection == Section.PICKS) {
                                populateSection(Section.PICKS, fromCloud = true)
                            }
                        }, 1500)
                    }
                }
            }
            starsRow.addView(star)
        }
    }

    /** Shared "save to My favorites" action behind both pick lists; urlKey
     *  dedup keeps entries from duplicating (aligned with the website). */
    private fun favoriteFromPick(title: String, url: String) {
        if (RelaxStore.isUrlFavorited(requireContext(), url)) {
            requireContext().toast(R.string.relax_already_in_favorites)
            return
        }
        RelaxStore.addCustomItem(requireContext(), title, url)
        requireContext().toast(R.string.relax_favorite_done)
    }

    // ---- 添加 / 编辑 / 推荐对话框 ----

    private fun showItemActionsDialog(item: RelaxItem) {
        val options = arrayOf(
            getString(R.string.relax_edit),
            getString(R.string.relax_remove)
        )
        requireActivity().getAlertDialogBuilder()
            .setTitle(item.title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showEditFavoriteDialog(item)
                    1 -> requireActivity().getAlertDialogBuilder()
                        .setMessage(R.string.relax_delete_confirm)
                        .setPositiveButton(R.string.relax_remove) { _, _ ->
                            RelaxStore.removeCustomItem(requireContext(), item.id)
                            populateSection(Section.FAVORITES)
                        }
                        .setNegativeButton(org.fossify.commons.R.string.cancel, null)
                        .show()
                }
            }
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .show()
    }

    private fun showEditFavoriteDialog(item: RelaxItem) {
        val holder = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                resources.displayMetrics.widthPixels / 10, 40,
                resources.displayMetrics.widthPixels / 10, 0
            )
        }

        val titleInput = EditText(requireContext()).apply {
            hint = getString(R.string.relax_title_hint)
            setText(item.title)
        }
        holder.addView(titleInput)

        // local content URIs are opaque grants - only the label is editable
        if (!item.isLocal) {
            val urlInput = EditText(requireContext()).apply {
                hint = getString(R.string.relax_url_hint)
                setText(item.url)
            }
            holder.addView(urlInput)

            val dialog = requireActivity().getAlertDialogBuilder()
                .setTitle(R.string.relax_edit)
                .setPositiveButton(org.fossify.commons.R.string.ok, null)
                .setNegativeButton(org.fossify.commons.R.string.cancel, null)
                .create()

            dialog.setOnShowListener {
                val okButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                okButton.isEnabled = item.title.isNotBlank()

                val watcher = object : TextWatcher {
                    override fun afterTextChanged(s: Editable?) {
                        okButton.isEnabled = titleInput.text.isNotBlank() &&
                            RelaxStore.isValidUrl(urlInput.text.toString())
                    }

                    override fun beforeTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) = Unit
                    override fun onTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) = Unit
                }
                titleInput.addTextChangedListener(watcher)
                urlInput.addTextChangedListener(watcher)

                okButton.setOnClickListener {
                    RelaxStore.updateCustomItem(
                        requireContext(),
                        item.id,
                        titleInput.text.toString().trim(),
                        RelaxStore.normalizeUrl(urlInput.text.toString())
                    )
                    dialog.dismiss()
                    populateSection(Section.FAVORITES)
                }
            }

            dialog.setView(holder)
            dialog.show()
            return
        }

        val dialog = requireActivity().getAlertDialogBuilder()
            .setTitle(R.string.relax_edit)
            .setView(holder)
            .setPositiveButton(org.fossify.commons.R.string.ok) { _, _ ->
                val title = titleInput.text.toString().trim()
                if (title.isNotEmpty()) {
                    RelaxStore.updateCustomItem(requireContext(), item.id, title, item.url)
                    populateSection(Section.FAVORITES)
                }
            }
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .create()
        dialog.show()
    }

    private fun showRecommendDialog() {
        val holder = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                resources.displayMetrics.widthPixels / 10, 40,
                resources.displayMetrics.widthPixels / 10, 0
            )
        }

        val titleInput = EditText(requireContext()).apply {
            hint = getString(R.string.relax_title_hint)
        }
        val urlInput = EditText(requireContext()).apply {
            hint = getString(R.string.relax_url_hint)
        }

        holder.addView(titleInput)
        holder.addView(urlInput)

        val typeLabels = InsomniaTypes.types.map { getString(it.labelRes) }.toTypedArray()
        val profileTypeIndex = InsomniaTypes.types.indexOfFirst {
            it.key == NightTalk.getProfile(requireContext()).insomniaType
        }
        var pickedType = if (profileTypeIndex >= 0) profileTypeIndex else -1

        val dialog = requireActivity().getAlertDialogBuilder()
            .setTitle(R.string.recommend_add)
            .setSingleChoiceItems(typeLabels, pickedType) { _, which ->
                pickedType = which
            }
            .setPositiveButton(org.fossify.commons.R.string.ok, null)
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            val okButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            okButton.isEnabled = false

            val inputWatcher = object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    okButton.isEnabled = titleInput.text.isNotBlank() &&
                        RelaxStore.isValidUrl(urlInput.text.toString())
                }

                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            }
            titleInput.addTextChangedListener(inputWatcher)
            urlInput.addTextChangedListener(inputWatcher)

            okButton.setOnClickListener {
                val title = titleInput.text.toString().trim()
                val url = RelaxStore.normalizeUrl(urlInput.text.toString())
                when (val verdict = AdGuard.check(title, url)) {
                    is AdGuard.Verdict.Blocked -> {
                        val reasonText = when (verdict.code) {
                            AdGuard.REASON_CONTACT ->
                                getString(R.string.ad_reason_contact, verdict.detail)

                            AdGuard.REASON_STRONG ->
                                getString(R.string.ad_reason_strong, verdict.detail)

                            else ->
                                getString(R.string.ad_reason_weak, verdict.detail)
                        }
                        requireContext().toast(getString(R.string.ad_blocked_toast, reasonText))
                    }

                    AdGuard.Verdict.Ok -> {
                        val pickedTypeKey =
                            if (pickedType >= 0) InsomniaTypes.types[pickedType].key else null
                        ensureBackgroundThread {
                            val ok = CommunityRemoteStore.add(
                                requireContext(), title, url, pickedTypeKey
                            )
                            activity?.runOnUiThread {
                                if (!isAdded) {
                                    return@runOnUiThread
                                }
                                if (ok) {
                                    dialog.dismiss()
                                    populateSection(Section.PICKS, fromCloud = true)
                                } else {
                                    requireContext().toast(R.string.community_cloud_failed)
                                }
                            }
                        }
                    }
                }
            }
        }

        dialog.setView(holder)
        dialog.show()
    }

    private fun showAddChoiceDialog() {
        val options = arrayOf(
            getString(R.string.relax_add_web),
            getString(R.string.relax_add_local)
        )

        requireActivity().getAlertDialogBuilder()
            .setTitle(R.string.relax_add_favorite)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showAddFavoriteDialog()
                    1 -> filePicker.launch(arrayOf("text/*", "audio/*", "application/epub+zip"))
                }
            }
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .show()
    }

    private fun handlePickedLocalFile(uri: Uri) {
        try {
            requireContext().contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: Exception) {
            // some providers do not hand out persistable grants,
            // the permission then only lasts until reboot
        }

        val title = queryDisplayName(uri) ?: getString(R.string.relax_local_label)
        RelaxStore.addCustomItem(requireContext(), title, uri.toString(), isLocal = true)
        populateSection(Section.FAVORITES)
    }

    private fun handleDataExport(uri: android.net.Uri) {
        val ok = RelaxDataIO.writeToUri(ioKind, requireContext(), uri)
        requireContext().toast(
            if (ok) R.string.relax_export_ok else R.string.relax_import_failed
        )
    }

    private fun handleDataImport(uri: android.net.Uri) {
        ensureBackgroundThread {
            val added = RelaxDataIO.mergeFromUri(ioKind, requireContext(), uri)
            activity?.runOnUiThread {
                if (!isAdded) {
                    return@runOnUiThread
                }
                when {
                    added < 0 -> requireContext().toast(R.string.relax_import_bad)
                    added == 0 -> requireContext().toast(R.string.relax_import_none)
                    else -> {
                        requireContext().toast(getString(R.string.relax_import_ok, added))
                        populateSection(currentSection)
                    }
                }
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            requireContext().contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun openItem(item: RelaxItem) {
        try {
            val intent = if (item.isLocal) {
                val uri = Uri.parse(item.url)
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, requireContext().contentResolver.getType(uri) ?: "*/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            } else {
                // normalize first: imports (e.g. website favorites) may store
                // bare urlKey strings without the https:// scheme
                Intent(Intent.ACTION_VIEW, RelaxStore.normalizeUrl(item.url).toUri())
            }
            startActivity(intent)
        } catch (e: Exception) {
            requireContext().toast(R.string.relax_invalid_url)
        }
    }

    private fun showAddFavoriteDialog() {
        val holder = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                resources.displayMetrics.widthPixels / 10, 40,
                resources.displayMetrics.widthPixels / 10, 0
            )
        }

        val pasteInput = EditText(requireContext()).apply {
            hint = getString(R.string.relax_paste_hint)
            maxLines = 4
        }
        val titleInput = EditText(requireContext()).apply {
            hint = getString(R.string.relax_title_optional_hint)
        }
        val urlInput = EditText(requireContext()).apply {
            hint = getString(R.string.relax_url_hint)
        }

        holder.addView(pasteInput)
        holder.addView(titleInput)
        holder.addView(urlInput)

        var userEditedTitle = false
        var fetchedTitle: String? = null
        val parseDebounce = android.os.Handler(android.os.Looper.getMainLooper())

        fun applyParsed(text: String) {
            val parsed = org.fossify.clock.helpers.LinkParser.parse(text)
            parsed.url?.let { urlInput.setText(it) }
            val shareTitle = parsed.title
            if (!shareTitle.isNullOrBlank() && !userEditedTitle) {
                titleInput.setText(shareTitle)
            } else if (shareTitle.isNullOrBlank()) {
                fetchedTitle = null
                val url = parsed.url ?: return
                org.fossify.clock.helpers.LinkParser.fetchTitleAsync(url) { remote ->
                    if (remote != null && !userEditedTitle) {
                        fetchedTitle = remote
                        if (titleInput.text.isNullOrBlank()) {
                            titleInput.setText(remote)
                        }
                    }
                }
            }
        }

        pasteInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                parseDebounce.removeCallbacksAndMessages(null)
                if (!s.isNullOrBlank()) {
                    parseDebounce.postDelayed({ applyParsed(s.toString()) }, 400)
                }
            }

            override fun beforeTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) = Unit
            override fun onTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) = Unit
        })
        titleInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                userEditedTitle = true
            }

            override fun beforeTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) = Unit
            override fun onTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) = Unit
        })

        val dialog = requireActivity().getAlertDialogBuilder()
            .setTitle(R.string.relax_add_favorite)
            .setPositiveButton(org.fossify.commons.R.string.ok, null)
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            val okButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            okButton.isEnabled = false

            val inputWatcher = object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    okButton.isEnabled = RelaxStore.isValidUrl(urlInput.text.toString())
                }

                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            }
            urlInput.addTextChangedListener(inputWatcher)

            okButton.setOnClickListener {
                val url = RelaxStore.normalizeUrl(urlInput.text.toString())
                if (!RelaxStore.isValidUrl(url)) {
                    return@setOnClickListener
                }
                if (RelaxStore.isUrlFavorited(requireContext(), url)) {
                    requireContext().toast(R.string.relax_already_in_favorites)
                    return@setOnClickListener
                }
                val host = try {
                    url.toUri().host ?: ""
                } catch (e: Exception) {
                    ""
                }
                val title = titleInput.text.toString().trim()
                    .ifEmpty { fetchedTitle ?: getString(R.string.relax_unnamed_fmt, host) }
                RelaxStore.addCustomItem(requireContext(), title, url)
                dialog.dismiss()
                populateSection(Section.FAVORITES)
            }
        }

        dialog.setView(holder)
        dialog.show()
    }

    private fun buildTypeChips() {
        chipsBuilt = true
        val group = binding.relaxTypeChips
        val entries = mutableListOf(InsomniaTypes.KEY_ALL)
        entries.addAll(InsomniaTypes.types.map { it.key })
        val chipViews = mutableListOf<org.fossify.commons.views.MyTextView>()

        entries.forEach { key ->
            val chip = LayoutInflater.from(requireContext())
                .inflate(R.layout.item_type_chip, group, false) as org.fossify.commons.views.MyTextView
            chip.text = if (key == InsomniaTypes.KEY_ALL) {
                getString(R.string.insomnia_all)
            } else {
                InsomniaTypes.labelKey(requireContext(), key)
            }
            chip.isSelected = key == selectedType
            chip.setTextColor(if (chip.isSelected) theme.grey else theme.sub)
            chip.setOnClickListener {
                if (selectedType == key) {
                    return@setOnClickListener
                }
                selectedType = key
                chipViews.forEach { v ->
                    val on = v.text == chip.text
                    v.isSelected = on
                    v.setTextColor(if (on) theme.grey else theme.sub)
                }
                populateSection(Section.PICKS, fromCloud = true)
            }
            chipViews.add(chip)
            group.addView(chip)
        }
    }
}
