// ── Java 标准库 ──
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.FileReader;
import java.io.BufferedReader;

// ── Android 框架 ──
import android.app.*;
import android.content.*;
import android.content.res.Configuration;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.view.*;
import android.widget.*;

// ── JSON ──
import org.json.JSONObject;
import org.json.JSONArray;

// ── Xposed ──
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodHook.MethodHookParam;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

private java.util.List<String> cachedOfficialWxids;
private java.util.List<String> cachedServiceWxids;
private java.util.List<String> cachedEnterpriseWxids;
private java.util.List<String[]> cachedLabels;
private java.util.Map<String, java.util.List<String>> cachedLabelMembers;
private final Object wcdbCacheLock = new Object();

private java.util.Set<String> ds_alreadyAdded    = new java.util.HashSet<>();
private java.util.Set<String> ds_officialFull    = new java.util.HashSet<>();
private java.util.Set<String> ds_serviceFull     = new java.util.HashSet<>();
private java.util.Set<String> ds_enterpriseFull  = new java.util.HashSet<>();
private java.util.List<String> ds_officialWxids   = new java.util.ArrayList<>();
private java.util.List<String> ds_serviceWxids    = new java.util.ArrayList<>();
private java.util.List<String> ds_enterpriseWxids = new java.util.ArrayList<>();
private java.util.List<String[]> ds_labels        = new java.util.ArrayList<>();
private java.util.Map<String, java.util.List<String>> ds_labelMembers = new java.util.HashMap<>();

private void resetDialogState() {
    ds_alreadyAdded    = new java.util.HashSet<>();
    ds_officialFull    = new java.util.HashSet<>();
    ds_serviceFull     = new java.util.HashSet<>();
    ds_enterpriseFull  = new java.util.HashSet<>();
    ds_officialWxids   = new java.util.ArrayList<>();
    ds_serviceWxids    = new java.util.ArrayList<>();
    ds_enterpriseWxids = new java.util.ArrayList<>();
    ds_labels          = new java.util.ArrayList<>();
    ds_labelMembers    = new java.util.HashMap<>();
}

Object hook;

void onLoad() {
    // 插件加载时的初始化
    try {
        // 清理旧的状态文件，避免干扰新的UI对话框方式
        File stateFile = new File(getGroupDir(), "selection_state.txt");
        if (stateFile.exists()) {
            stateFile.delete();
        }
    } catch (Exception e) {
        // 忽略清理错误
    }

    // 启动时加载头像URL黑名单（避免重启后仍对无URL的wxid反复调用API）
    new Thread(new Runnable() {
        public void run() { loadNoUrlCache(); }
    }).start();

    // 挂钩右上角更多功能按钮，添加长按监听
    try {
        hook = XposedBridge.hookMethod(
            View.class.getDeclaredMethod("onAttachedToWindow"),
            new XC_MethodHook() {
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    View view = (View) param.thisObject;
                    if (view == null) return;
                    if (view instanceof ImageView) {
                        ImageView iv = (ImageView) view;
                        CharSequence desc = iv.getContentDescription();
                        if (desc != null && desc.toString().matches("(更多功能按钮|更多信息|设置|聊天信息)")) {
                            view.post(new Runnable() {
                                public void run() {
                                    iv.setOnLongClickListener(new View.OnLongClickListener() {
                                        public boolean onLongClick(View v) {
                                            showAddGroupDialog();
                                            return true;
                                        }
                                        public boolean onLongClickUseDefaultHapticFeedback(View v) {
                                            return true;
                                        }
                                    });
                                }
                            });
                        }
                    }
                }
            });
    } catch (Throwable e) {
        uiToast("挂钩失败: " + e.getMessage());
    }
}

void onUnLoad() {
    if (hook != null) {
        hook.unhook();
    }
}

/**
 * 监听收到消息回调（空实现，避免 WAuxiliary 调用时报 null 错误）
 */
void onHandleMsg(Object msgInfoBean) {
    // 本插件不处理消息，留空实现防止框架调用异常
    // 注意：WAuxiliary 调用此方法时若抛出异常会显示"解析数据时发生异常"
    // 保持完全空实现，不做任何操作（包括不打日志，避免日志API本身出问题）
}

/**
 * 单击发送按钮回调
 */
boolean onClickSendBtn(String text) {
    if ("分组管理".equals(text == null ? "" : text.trim())) {
        showAddGroupDialog();
        return true;
    }
    return false;
}

/**
 * 长按右上角按钮回调，用于显示添加分组对话框
 */
boolean showAddGroupDialog() {
    if (isDialogShowing) {
        return true;
    }

    String currentWxid = getTargetTalker();
    if (currentWxid == null || currentWxid.isEmpty()) {
        toast("❌ 无法获取当前聊天对象wxid");
        return true;
    }

    // 每次打开主对话框都触发一次头像预热
    // preloadAvatars 内部通过 avatarBitmapCache / diskFile.exists() 自然跳过已有数据，
    // avatarInitialLoadDone 仅用于区分"首次需要网络下载"和"后续仅补充内存缓存"
    new Thread(new Runnable() {
        public void run() {
            try {
                if (!avatarInitialLoadDone) Thread.sleep(1000); // 首次稍等对话框初始化
                getCachedFriendList();
                loadWcdbOfficialAccounts(cachedFriendNameMap);

                // 在锁内取快照，避免与其他线程重建缓存时产生ConcurrentModificationException
                java.util.List<String> allWxids = new java.util.ArrayList<>();
                synchronized (wcdbCacheLock) {
                    if (cachedFriendWxidList  != null) allWxids.addAll(cachedFriendWxidList);
                    if (cachedOfficialWxids   != null) allWxids.addAll(cachedOfficialWxids);
                    if (cachedServiceWxids    != null) allWxids.addAll(cachedServiceWxids);
                    if (cachedEnterpriseWxids != null) allWxids.addAll(cachedEnterpriseWxids);
                }

                if (!allWxids.isEmpty()) {
                    log("头像预热：共 " + allWxids.size() + " 个（含公众号），仅磁盘加载");
                    int diskHit = 0, diskMiss = 0;
                    for (String wxid : allWxids) {
                        if (wxid == null || wxid.isEmpty()) continue;
                        if (avatarBitmapCache.containsKey(wxid)) continue;
                        // ★ 关键修复：只从磁盘读入内存，绝不调用 getAvatarUrl/download。
                        // preloadAvatar → submitAvatarTask → getAvatarUrl 是 WAuxiliary 网络 API，
                        // 对 368 个联系人并发调用会触发 WAuxiliary 内部钩子异常，显示"解析数据时发生异常"。
                        // 实际下载由用户打开批量添加对话框时的 loadAvatar 按需触发即可。
                        File diskFile = getAvatarFile(wxid);
                        if (diskFile.exists()) {
                            final File f = diskFile;
                            final String w = wxid;
                            new Thread(new Runnable() {
                                public void run() { decodeAndShow(f, w, null); }
                            }).start();
                            diskHit++;
                        } else {
                            // 磁盘无文件：不在此处调用getAvatarUrl，留给批量添加按需下载
                            // 已在黑名单中的直接跳过（避免重启后仍触发无效API调用）
                            if (!avatarNoUrlSet.contains(wxid)) diskMiss++;
                        }
                    }
                    log("头像预热完成：磁盘命中=" + diskHit + " 磁盘未命中(URL可获取但下载未完成，打开批量添加时会重试)=" + diskMiss + " 黑名单跳过=" + avatarNoUrlSet.size());
                    avatarInitialLoadDone = true;
                }
            } catch (Exception e) {
                log("头像预热失败: " + e.getMessage());
            }
        }
    }).start();

    // fix⑫: 文件IO移到子线程，避免主线程卡顿
    new Thread(new Runnable() {
        public void run() {
            try {
                File targetDir = getGroupDir();
                if (!targetDir.exists()) {
                    targetDir.mkdirs();
                }
                File groupFile = new File(targetDir, "groupItemsV2.json");

                JSONArray groupItems;
                // fix②: 文件不存在时自动创建默认分组文件，而非报错返回
                if (!groupFile.exists()) {
                    groupItems = new JSONArray();
                    String[] types  = new String[]{"all", "group", "friend", "official", "custom"};
                    String[] titles = new String[]{"全部", "群聊", "好友", "官方", "示例"};
                    int[] orders    = new int[]{0, 1, 2, 3, 4};
                    for (int di = 0; di < titles.length; di++) {
                        JSONObject dg = new JSONObject();
                        dg.put("type", types[di]);
                        dg.put("title", titles[di]);
                        dg.put("order", orders[di]);
                        dg.put("enable", true);
                        dg.put("idList", new JSONArray());
                        groupItems.put(dg);
                    }
                    FileWriter fw = null;
                    try {
                        fw = new FileWriter(groupFile);
                        fw.write(groupItems.toString(2));
                    } finally {
                        if (fw != null) { try { fw.close(); } catch (Exception ignore) {} }
                    }
                } else {
                    StringBuilder content = new StringBuilder();
                    BufferedReader reader = null;
                    try {
                        reader = new BufferedReader(new FileReader(groupFile));
                        String line;
                        while ((line = reader.readLine()) != null) {
                            content.append(line);
                        }
                    } finally {
                        if (reader != null) { try { reader.close(); } catch (Exception ignore) {} }
                    }
                    try {
                        groupItems = new JSONArray(content.toString());
                    } catch (org.json.JSONException e) {
                        log("解析分组文件失败，重置为空: " + e.getMessage());
                        groupItems = new JSONArray();
                    }
                }

                final JSONArray finalGroupItems = groupItems;
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    public void run() {
                        try {
                            showGroupSelectionDialog(finalGroupItems, currentWxid, groupFile);
                        } catch (Throwable e) {
                            log("[selection] post Runnable 异常: " + e.getClass().getName() + ": " + e.getMessage());
                            uiToast("❌ 打开分组列表失败");
                        }
                    }
                });

            } catch (Exception e) {
                log("处理分组失败: " + e.getMessage());
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    public void run() {
                        toast("❌ 处理分组失败");
                    }
                });
            }
        }
    }).start();

    return true;
}

// UI主题相关变量
String C_BG_ROOT, C_TEXT_PRIMARY, C_TEXT_SECONDARY, C_CARD_BG, C_CARD_STROKE, C_DIVIDER, C_BUTTON_BG, C_BUTTON_TEXT, C_HINT_TEXT, C_ACCENT;
// 预解析的 int 颜色，供 shapeStrokeInt 使用，避免每次 parseColor
int CI_BG_ROOT, CI_TEXT_PRIMARY, CI_TEXT_SECONDARY, CI_CARD_BG, CI_CARD_STROKE, CI_DIVIDER, CI_BUTTON_BG, CI_BUTTON_TEXT, CI_HINT_TEXT, CI_ACCENT;

// 对话框状态控制
private boolean isDialogShowing = false;

// UI组件ID常量
private static final int ID_INFO_TEXT        = 0x7F000001;
private static final int ID_FRIEND_CONTAINER = 0x7F000002;
private static final int ID_GROUP_CONTAINER  = 0x7F000003;
private static final int ID_MEMBER_CONTAINER = 0x7F000004; // fix⑩: 统一使用此常量
private static final int ID_MEMBER_TITLE     = 0x7F000005;
private static final int ID_SEARCH_BOX      = 0x7F000006;
private static final int ID_SEARCH_BUTTON   = 0x7F000007;

// 头像内存缓存，最多保留128张（约6MB），超出时清空一半旧条目
private java.util.Map<String, Bitmap> avatarBitmapCache =
    java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<String, Bitmap>(128, 0.75f, true));
private static final int AVATAR_CACHE_MAX = 128;

// 头像正在加载中的wxid集合（防止同一wxid同时发起多个下载）
private java.util.Set<String> avatarLoadingSet =
    java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

// 下载进行中时，注册等待同一 wxid 结果的 ImageView 列表
private java.util.Map<String, java.util.List<ImageView>> avatarPendingViews =
    java.util.Collections.synchronizedMap(new java.util.HashMap<String, java.util.List<ImageView>>());

// 后台下载并发限制（信号量），适配3000+好友不把系统打崩
private java.util.concurrent.Semaphore avatarSemaphore =
    new java.util.concurrent.Semaphore(8, true);

// 头像URL黑名单：getAvatarUrl返回空的wxid，持久化到 avatars/no_url.json，重启后跳过
// 避免对无法获取URL的wxid反复调用WAuxiliary网络API导致框架报错"解析数据时发生异常"
private java.util.Set<String> avatarNoUrlSet =
    java.util.Collections.synchronizedSet(new java.util.HashSet<String>());
private final Object noUrlFileLock = new Object();

// 标记是否已完成过首次全量下载（onLoad触发）
private boolean avatarInitialLoadDone = false;

// 好友列表缓存
private java.util.List<String> cachedFriendWxidList = null;
private java.util.Map<String, String> cachedFriendNameMap = null;
private long friendListCacheTime = 0;
private long officialCacheTime = 0;   // 公众号+标签缓存时间戳（同 CACHE_DURATION）
private static final long CACHE_DURATION = 5 * 60 * 1000; // 5分钟缓存

// 搜索预处理索引：wxid → lowercase(昵称+wxid)，在 getCachedFriendList 后台一次性生成
private java.util.Map<String, String> cachedSearchIndex = new java.util.HashMap<>();

/**
 * 分批渲染联系人卡片
 *
 * 优化策略（五项组合）：
 * ① 先建后换：先在内存构建本批所有 View，再一次性 addView，消除"切换空白期"
 * ② 首批8条：首屏只渲染可见数量，<16ms 内完成，用户感知秒开；后续50条
 * ③ 初始渲染 postDelayed(0)：去掉 100ms 延迟，打开即渲染
 * ④ dp/LayoutParams 批次外预算，避免重复计算
 * ⑤ GONE→addAll→VISIBLE：N 次 layout pass → 1 次，彻底消除 Double Measure
 *
 * container：当前 Tab 对应的独立容器（三 Tab 各自一个）
 * onComplete：全部批次渲染完毕后的回调（用于触发后台预渲染），可为 null
 */
/**
 * 构建单个联系人卡片行，供 renderContactBatch 和 renderAllTabBatch 共用
 * 注意：avLP 含 margins，必须每次 new，不能在循环外共享
 */


/**
 * 构建单个联系人卡片行（buildContactRow）
 * 供 renderContactBatch / renderAllTabBatch 共用，消除重复渲染代码。
 * avLP 含 margins，BUG：多 View 共享会被 Android 回写 resolvedMarginStart/End，
 * 因此在本方法内部每次 new，由调用方传入无 margin 的 rowLP/cbLP/infoLP 复用。
 */
private LinearLayout buildContactRow(Activity act,
                                      String wxid,
                                      java.util.Map<String, String> nameMap,
                                      java.util.Set<String> selectedWxids,
                                      LinearLayout.LayoutParams rowLP,
                                      LinearLayout.LayoutParams cbLP,
                                      LinearLayout.LayoutParams infoLP,
                                      int dp6, int dp8, int dp12, int dp40) {
    String name = nameMap.get(wxid);
    if (name == null || name.isEmpty()) name = wxid;

    final LinearLayout row = new LinearLayout(act);
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setPadding(dp12, dp8, dp12, dp8);
    row.setBackground(shapeStrokeInt(CI_CARD_BG, dp6, CI_DIVIDER));
    row.setTag(wxid);
    row.setLayoutParams(rowLP);

    CheckBox cb = new CheckBox(act);
    cb.setClickable(false);
    cb.setFocusable(false);
    cb.setChecked(selectedWxids.contains(wxid));
    cb.setLayoutParams(cbLP);

    ImageView av = new ImageView(act);
    LinearLayout.LayoutParams avLP = new LinearLayout.LayoutParams(dp40, dp40);
    avLP.setMargins(dp8, 0, dp12, 0);
    av.setLayoutParams(avLP);
    av.setScaleType(ImageView.ScaleType.CENTER_CROP);
    // 灰色圆角占位，尺寸与真实头像一致，避免默认系统图标过小
    // 注意：BeanShell中0xFF...超过Integer.MAX_VALUE会被当long，必须强转int
    GradientDrawable placeholder = new GradientDrawable();
    placeholder.setColor(Color.GRAY); // Color.GRAY = 0xFF888888，BeanShell不支持(int)强转hex字面量
    placeholder.setCornerRadius(dp8);
    av.setBackground(placeholder);
    loadAvatar(av, wxid);

    LinearLayout info = new LinearLayout(act);
    info.setOrientation(LinearLayout.VERTICAL);
    info.setLayoutParams(infoLP);

    TextView nameTV = new TextView(act);
    nameTV.setText(name);
    nameTV.setTextSize(14);
    styleTextPrimary(nameTV);
    info.addView(nameTV);

    TextView wxidTV = new TextView(act);
    wxidTV.setText(wxid);
    wxidTV.setTextSize(11);
    styleTextSecondary(wxidTV);
    info.addView(wxidTV);

    row.addView(cb);
    row.addView(av);
    row.addView(info);

    // 通过 row.getTag() 而非捕获循环变量来读取 wxid，
    // 避免 BeanShell 匿名类闭包共享最后一次迭代引用的 Bug
    row.setOnClickListener(new View.OnClickListener() {
        public void onClick(View v) {
            LinearLayout r = (LinearLayout) v;
            View first = r.getChildAt(0);
            if (!(first instanceof CheckBox)) return;
            CheckBox box = (CheckBox) first;
            boolean next = !box.isChecked();
            box.setChecked(next);
            Object tag = r.getTag();
            if (tag == null) return;
            String id = tag.toString();
            if (next) selectedWxids.add(id);
            else selectedWxids.remove(id);
        }
    });

    return row;
}

/**
 * ListView复用时更新已有行的数据（不重建View，只改文字/CheckBox/头像）
 * row.getTag() 存的是旧wxid，需要先从旧状态复位再写入新数据
 */
private void bindContactRow(LinearLayout row, String wxid,
                             java.util.Map<String, String> nameMap,
                             java.util.Set<String> selectedWxids) {
    try {
        row.setTag(wxid);
        String name = (String) nameMap.get(wxid);
        if (name == null || name.isEmpty()) name = wxid;

        // row结构：CheckBox(0) + ImageView(1) + LinearLayout(info)(2)
        // info结构：TextView(name)(0) + TextView(wxid)(1)
        View child0 = row.getChildAt(0);
        View child1 = row.getChildAt(1);
        View child2 = row.getChildAt(2);

        if (child0 instanceof CheckBox) {
            ((CheckBox) child0).setChecked(selectedWxids.contains(wxid));
        }
        if (child1 instanceof ImageView) {
            ImageView av = (ImageView) child1;
            // 先重置为灰色占位，再异步加载，防止复用时显示旧头像
            av.setImageBitmap(null);
            loadAvatar(av, wxid);
        }
        if (child2 instanceof LinearLayout) {
            LinearLayout info = (LinearLayout) child2;
            View n = info.getChildAt(0);
            View w = info.getChildAt(1);
            if (n instanceof TextView) ((TextView) n).setText(name);
            if (w instanceof TextView) ((TextView) w).setText(wxid);
        }
    } catch (Exception e) {
        log("bindContactRow 异常: " + e.getMessage());
    }
}

/**
 * 分批渲染联系人列表（首批 8 条覆盖首屏，后续每批 50 条）
 * GONE→addAll→VISIBLE 模式：整个批次只触发 1 次 layout
 */
private void renderContactBatch(final Activity act,
                                final LinearLayout container,
                                final java.util.List<String> displayList,
                                final java.util.Map<String, String> nameMap,
                                final java.util.Set<String> selectedWxids,
                                final int[] renderVersion, final int myVer,
                                final int startIndex,
                                final Runnable onComplete) {
    if (renderVersion[0] != myVer) return;

    final int batchSize = (startIndex == 0) ? 8 : 50;
    final int end = Math.min(startIndex + batchSize, displayList.size());

    final int dp6 = dp(6), dp8 = dp(8), dp12 = dp(12), dp40 = dp(40);
    final LinearLayout.LayoutParams rowLP = new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    rowLP.bottomMargin = dp6;
    final LinearLayout.LayoutParams cbLP = new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    final LinearLayout.LayoutParams infoLP = new LinearLayout.LayoutParams(
        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);

    java.util.List<View> rows = new java.util.ArrayList<>();
    for (int i = startIndex; i < end; i++) {
        if (i >= displayList.size()) break;
        rows.add(buildContactRow(act, (String) displayList.get(i), nameMap, selectedWxids,
                                 rowLP, cbLP, infoLP, dp6, dp8, dp12, dp40));
    }

    container.setVisibility(View.GONE);
    if (startIndex == 0) container.removeAllViews();
    for (int i = 0; i < rows.size(); i++) container.addView(rows.get(i));
    container.setVisibility(View.VISIBLE);

    if (end < displayList.size()) {
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            public void run() {
                renderContactBatch(act, container, displayList, nameMap,
                                   selectedWxids, renderVersion, myVer, end, onComplete);
            }
        }, 16);
    } else {
        if (onComplete != null) onComplete.run();
    }
}



/**
 * 渲染"全部" Tab：将好友/群聊/订阅号/服务号四个列表合并成带分类标题的有序列表，
 * 然后委托 renderAllTabBatch 分批渲染。
 *
 * 调用方只需传入四个已过滤/排序好的列表，本方法负责插入 __header_xxx__ 占位 key。
 */
private void renderAllTabWithHeaders(final Activity act,
                                      final LinearLayout container,
                                      final java.util.List<String> friendList,
                                      final java.util.List<String> groupList,
                                      final java.util.List<String> officialList,
                                      final java.util.List<String> serviceList,
                                      final java.util.Map<String, String> nameMap,
                                      final java.util.Set<String> selectedWxids,
                                      final int[] renderVersion, final int myVer,
                                      final Runnable onComplete) {
    // 构建有序 key 列表：每类非空才插入对应 header，保持好友→群聊→订阅号→服务号顺序
    final java.util.List<String> orderedKeys = new java.util.ArrayList<>();
    final java.util.Set<String>  headerKeys  = new java.util.HashSet<>();

    if (!friendList.isEmpty()) {
        orderedKeys.add("__header_friend__");
        headerKeys.add("__header_friend__");
        orderedKeys.addAll(friendList);
    }
    if (!groupList.isEmpty()) {
        orderedKeys.add("__header_group__");
        headerKeys.add("__header_group__");
        orderedKeys.addAll(groupList);
    }
    if (!officialList.isEmpty()) {
        orderedKeys.add("__header_official__");
        headerKeys.add("__header_official__");
        orderedKeys.addAll(officialList);
    }
    if (!serviceList.isEmpty()) {
        orderedKeys.add("__header_service__");
        headerKeys.add("__header_service__");
        orderedKeys.addAll(serviceList);
    }

    renderAllTabBatch(act, container, orderedKeys, headerKeys, nameMap,
                      selectedWxids, renderVersion, myVer, 0, onComplete);
}

/**
 * 分批渲染"全部" Tab，header key 插入分类标题行，普通 key 委托 buildContactRow
 */
private void renderAllTabBatch(final Activity act,
                                final LinearLayout container,
                                final java.util.List<String> orderedKeys,
                                final java.util.Set<String> headerKeys,
                                final java.util.Map<String, String> nameMap,
                                final java.util.Set<String> selectedWxids,
                                final int[] renderVersion, final int myVer,
                                final int startIndex,
                                final Runnable onComplete) {
    if (renderVersion[0] != myVer) return;

    final int batchSize = (startIndex == 0) ? 8 : 50;
    final int end = Math.min(startIndex + batchSize, orderedKeys.size());

    final int dp4 = dp(4), dp6 = dp(6), dp8 = dp(8), dp12 = dp(12), dp40 = dp(40);
    final LinearLayout.LayoutParams rowLP = new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    rowLP.bottomMargin = dp6;
    final LinearLayout.LayoutParams cbLP = new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    final LinearLayout.LayoutParams infoLP = new LinearLayout.LayoutParams(
        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);

    java.util.List<View> views = new java.util.ArrayList<>();
    for (int i = startIndex; i < end; i++) {
        String key = (String) orderedKeys.get(i);

        if (headerKeys.contains(key)) {
            String label;
            if      ("__header_friend__".equals(key))   label = "👤 好友";
            else if ("__header_group__".equals(key))    label = "💬 群聊";
            else if ("__header_official__".equals(key)) label = "📢 订阅号";
            else                                        label = "🏢 服务号";

            int cnt = 0;
            for (int j = i + 1; j < orderedKeys.size(); j++) {
                if (headerKeys.contains(orderedKeys.get(j))) break;
                cnt++;
            }

            TextView h = new TextView(act);
            h.setText(label + " (" + cnt + ")");
            h.setTextSize(13);
            h.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            styleTextSecondary(h);
            h.setPadding(dp8, dp6, dp8, dp4);
            LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            hlp.topMargin    = (i == 0) ? 0 : dp8;
            hlp.bottomMargin = dp4;
            h.setLayoutParams(hlp);
            views.add(h);
        } else {
            views.add(buildContactRow(act, key, nameMap, selectedWxids,
                                      rowLP, cbLP, infoLP, dp6, dp8, dp12, dp40));
        }
    }

    container.setVisibility(View.GONE);
    if (startIndex == 0) container.removeAllViews();
    for (int i = 0; i < views.size(); i++) container.addView(views.get(i));
    container.setVisibility(View.VISIBLE);

    if (end < orderedKeys.size()) {
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            public void run() {
                renderAllTabBatch(act, container, orderedKeys, headerKeys, nameMap,
                                  selectedWxids, renderVersion, myVer, end, onComplete);
            }
        }, 16);
    } else {
        if (onComplete != null) onComplete.run();
    }
}



private void filterContacts(java.util.List<String> original,
                            java.util.List<String> result,
                            java.util.Map<String, String> nameMap,
                            int tab, String keyword) {
    result.clear();
    String kw = (keyword == null) ? "" : keyword.toLowerCase().trim();
    for (String wxid : original) {
        boolean isGroup = wxid.endsWith("@chatroom");
        if (tab == 1 && isGroup) continue;
        if (tab == 2 && !isGroup) continue;
        if (!kw.isEmpty()) {
            String idx = cachedSearchIndex.get(wxid);
            if (idx == null) {
                String n = nameMap.get(wxid); if (n == null) n = "";
                idx = (n + " " + wxid).toLowerCase();
            }
            if (!idx.contains(kw)) continue;
        }
        result.add(wxid);
    }
}

// ==================== Dialog UI 辅助层 ====================

/**
 * 构建标准对话框（圆角卡片 + 居中大标题）
 * 消除三处对话框方法里重复的 Dialog/root/WindowManager 模板代码
 */
// Object[] layout: {Dialog dialog, LinearLayout root, int screenWidth, int screenHeight}
// BeanShell 不支持自定义 class，用 Object[] 返回多值替代 DialogSpec
private Object[] buildStandardDialog(Activity act, String titleText) {
    Dialog dialog = new Dialog(act);
    dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
    dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

    WindowManager wm = (WindowManager) act.getSystemService(Context.WINDOW_SERVICE);
    int sw = wm.getDefaultDisplay().getWidth();
    int sh = wm.getDefaultDisplay().getHeight();

    LinearLayout root = new LinearLayout(act);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setPadding(dp(32), dp(32), dp(32), dp(24));
    root.setBackground(shapeInt(CI_BG_ROOT, dp(24)));

    if (titleText != null && !titleText.isEmpty()) {
        TextView tv = new TextView(act);
        tv.setText(titleText);
        tv.setTextSize(20);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        styleTextPrimary(tv);
        tv.setGravity(Gravity.CENTER_HORIZONTAL);
        tv.setPadding(0, 0, 0, dp(20));
        root.addView(tv);
    }

    return new Object[]{dialog, root, Integer.valueOf(sw), Integer.valueOf(sh)};
}

/**
 * buildStandardDialog 重载：支持自定义标题字号和根布局 padding
 * 批量添加对话框使用较小字号（18sp）和较紧 padding
 */
private Object[] buildStandardDialog(Activity act, String titleText, int titleSizeSp,
                                        int padL, int padT, int padR, int padB) {
    Object[] spec = buildStandardDialog(act, titleText);
    LinearLayout root = (LinearLayout) spec[1];
    root.setPadding(padL, padT, padR, padB);
    if (root.getChildCount() > 0 && root.getChildAt(0) instanceof TextView) {
        ((TextView) root.getChildAt(0)).setTextSize(titleSizeSp);
    }
    return spec;
}

/** 展示并调整 Dialog 尺寸，宽度为屏幕 90%，高度为屏幕 85% */
private void showDialogFullish(Object[] spec) {
    Dialog dialog = (Dialog) spec[0];
    LinearLayout root = (LinearLayout) spec[1];
    int sw = ((Integer) spec[2]).intValue();
    int sh = ((Integer) spec[3]).intValue();
    dialog.setContentView(root);
    try {
        dialog.show();
        Window w = dialog.getWindow();
        if (w != null) {
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.width  = Math.min((int)(sw * 0.9f), sw - dp(32));
            lp.height = (int)(sh * 0.85f);
            w.setAttributes(lp);
        }
    } catch (Throwable e) {
        log("showDialogFullish 失败: " + e.getMessage());
    }
}

/** 展示并调整 Dialog 宽度为屏幕 90%，高度 WRAP */
private void showDialogWrap(Object[] spec) {
    Dialog dialog = (Dialog) spec[0];
    LinearLayout root = (LinearLayout) spec[1];
    int sw = ((Integer) spec[2]).intValue();
    dialog.setContentView(root);
    try {
        dialog.show();
        Window w = dialog.getWindow();
        if (w != null) {
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.width = Math.min((int)(sw * 0.9f), sw - dp(32));
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
            w.setAttributes(lp);
        }
    } catch (Throwable e) {
        log("showDialogWrap 失败: " + e.getMessage());
    }
}

/**
 * 检测是否为深色模式
 */
boolean isDarkMode() {
    try {
        Activity a = getTopActivity();
        if (a == null) return false;
        int m = a.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return m == Configuration.UI_MODE_NIGHT_YES;
    } catch (Throwable e) {
        return false;
    }
}

/**
 * 应用主题
 */
void applyTheme() {
    boolean d = isDarkMode();
    if (d) {
        C_BG_ROOT = "#1F1F1F";
        C_TEXT_PRIMARY = "#FFFFFF";
        C_TEXT_SECONDARY = "#A6A6A6";
        C_CARD_BG = "#242424";
        C_CARD_STROKE = "#333333";
        C_DIVIDER = "#333333";
        C_BUTTON_BG = "#2E2E2E";
        C_BUTTON_TEXT = "#FFFFFF";
        C_HINT_TEXT = "#9AA0A6";
        C_ACCENT = "#4C8BF5";
    } else {
        C_BG_ROOT = "#FCFBF8";
        C_TEXT_PRIMARY = "#333333";
        C_TEXT_SECONDARY = "#777777";
        C_CARD_BG = "#FFFFFF";
        C_CARD_STROKE = "#FFDAB9";
        C_DIVIDER = "#D8BFD8";
        C_BUTTON_BG = "#FFDAB9";
        C_BUTTON_TEXT = "#333333";
        C_HINT_TEXT = "#777777";
        C_ACCENT = "#D8BFD8";
    }
    // 预解析全部颜色 int，UI 代码统一使用 CI_ 版本，避免重复 parseColor
    try {
        CI_BG_ROOT        = Color.parseColor(C_BG_ROOT);
        CI_TEXT_PRIMARY   = Color.parseColor(C_TEXT_PRIMARY);
        CI_TEXT_SECONDARY = Color.parseColor(C_TEXT_SECONDARY);
        CI_CARD_BG        = Color.parseColor(C_CARD_BG);
        CI_CARD_STROKE    = Color.parseColor(C_CARD_STROKE);
        CI_DIVIDER        = Color.parseColor(C_DIVIDER);
        CI_BUTTON_BG      = Color.parseColor(C_BUTTON_BG);
        CI_BUTTON_TEXT    = Color.parseColor(C_BUTTON_TEXT);
        CI_HINT_TEXT      = Color.parseColor(C_HINT_TEXT);
        CI_ACCENT         = Color.parseColor(C_ACCENT);
    } catch (Throwable ignore) {}
}

/**
 * 创建渐变背景
 */
GradientDrawable createGradientBg(int orientation, int[] colors, int radius) {
    GradientDrawable gd = new GradientDrawable(
        GradientDrawable.Orientation.values()[orientation], colors);
    gd.setCornerRadius(radius);
    return gd;
}

/**
 * 创建形状
 */
/** 创建纯色圆角形状（字符串版，内部委托 int 版；仅供外部硬编码颜色调用） */
GradientDrawable shape(String color, int radius) {
    int c = 0;
    try { c = Color.parseColor(color); } catch (Throwable ignore) {}
    return shapeInt(c, radius);
}

/** 创建纯色圆角形状（int 版，核心实现） */
GradientDrawable shapeInt(int color, int radius) {
    GradientDrawable g = new GradientDrawable();
    g.setColor(color);
    g.setCornerRadius(radius);
    return g;
}

/**
 * 创建带边框的形状（字符串版，委托 shapeStrokeInt，仅用于硬编码颜色场景）
 */
GradientDrawable shapeStroke(String fill, int radius, String stroke) {
    int fillColor = 0, strokeColor = 0;
    try { fillColor  = Color.parseColor(fill);   } catch (Throwable ignore) {}
    try { strokeColor = Color.parseColor(stroke); } catch (Throwable ignore) {}
    return shapeStrokeInt(fillColor, radius, strokeColor);
}

/**
 * 创建带边框的形状（int颜色版，核心实现）
 */
GradientDrawable shapeStrokeInt(int fill, int radius, int stroke) {
    GradientDrawable g = new GradientDrawable();
    g.setColor(fill);
    g.setCornerRadius(radius);
    g.setStroke(1, stroke);
    return g;
}

/**
 * 创建按钮
 */
Button btn(Context c, String t) {
    Button b = new Button(c);
    b.setText(t);
    b.setAllCaps(false);
    b.setPadding(24, 12, 24, 12);
    try {
        b.setTextColor(CI_BUTTON_TEXT);
        // 浅色：桃色→薰衣草渐变；深色：纯色描边
        if (!isDarkMode()) {
            GradientDrawable gd = createGradientBg(
                GradientDrawable.Orientation.TL_BR.ordinal(),
                new int[]{CI_BUTTON_BG, CI_ACCENT}, dp(50));
            gd.setGradientType(GradientDrawable.LINEAR_GRADIENT);
            b.setBackground(gd);
        } else {
            b.setBackground(shapeStrokeInt(CI_BUTTON_BG, dp(16), CI_CARD_STROKE));
        }
    } catch (Throwable ignore) {
    }
    return b;
}

/**
 * 创建小型次要按钮（搜索/清空/全选/反选/↑↓ 等）
 * 统一替代散落各处的 new Button + 手动设样式
 */
Button btnSmall(Context c, String text) {
    Button b = new Button(c);
    b.setText(text);
    b.setAllCaps(false);
    b.setTextSize(12);
    b.setPadding(dp(12), dp(6), dp(12), dp(6));
    b.setBackground(shapeStrokeInt(CI_BUTTON_BG, dp(4), CI_CARD_STROKE));
    b.setTextColor(CI_BUTTON_TEXT);
    return b;
}

/**
 * 创建加号按钮
 */
Button createPlusButton(Context c) {
    Button b = new Button(c);
    b.setText("+");
    b.setAllCaps(false);
    b.setTextSize(24);
    b.setPadding(dp(16), dp(8), dp(16), dp(8));
    try {
        b.setTextColor(CI_BUTTON_TEXT);
        GradientDrawable circleBg = new GradientDrawable();
        circleBg.setShape(GradientDrawable.OVAL);
        circleBg.setColor(CI_ACCENT);
        b.setBackground(circleBg);
    } catch (Throwable ignore) {
    }
    return b;
}

/**
 * 设置文本主样式
 */
void styleTextPrimary(TextView tv) {
    try {
        tv.setTextColor(CI_TEXT_PRIMARY);
    } catch (Throwable ignore) {
    }
}

/**
 * 设置文本次要样式
 */
void styleTextSecondary(TextView tv) {
    try {
        tv.setTextColor(CI_TEXT_SECONDARY);
    } catch (Throwable ignore) {
    }
}

/**
 * 设置标题样式
 */
void styleHeader(TextView tv) {
    try {
        tv.setTextSize(16f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(CI_TEXT_PRIMARY);
        tv.setPadding(0, 8, 0, 8);
    } catch (Throwable ignore) {
    }
}

/**
 * 创建成员信息视图
 */
private View createMemberInfoView(Context context, String wxid) {
    String friendName = "";
    try {
        friendName = getFriendName(wxid);
    } catch (Throwable e) {}

    LinearLayout infoLayout = new LinearLayout(context);
    infoLayout.setOrientation(LinearLayout.VERTICAL);
    infoLayout.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));

    if (!friendName.isEmpty() && !friendName.equals(wxid)) {
        TextView nameText = new TextView(context);
        nameText.setText(friendName);
        nameText.setTextSize(14);
        styleTextPrimary(nameText);
        infoLayout.addView(nameText);
    }

    TextView wxidText = new TextView(context);
    wxidText.setText(wxid);
    wxidText.setTextSize(12);
    styleTextSecondary(wxidText);
    infoLayout.addView(wxidText);

    return infoLayout;
}

/**
 * 获取头像磁盘缓存目录（{groupDir}/avatars/）
 */
private File getAvatarCacheDir() {
    File dir = new File(getGroupDir(), "avatars");
    if (!dir.exists()) dir.mkdirs();
    return dir;
}

/**
 * 获取 wxid 对应的磁盘缓存文件
 */
private File getAvatarFile(String wxid) {
    String safeName = wxid.replace("@", "_").replace(":", "_").replace("/", "_");
    return new File(getAvatarCacheDir(), safeName + ".png");
}

/**
 * 头像URL黑名单文件路径
 */
private File getNoUrlFile() {
    return new File(getAvatarCacheDir(), "no_url.json");
}

/**
 * 从磁盘加载黑名单到内存（插件加载或首次使用时调用一次）
 */
private void loadNoUrlCache() {
    synchronized (noUrlFileLock) {
        try {
            File f = getNoUrlFile();
            if (!f.exists()) return;
            StringBuilder sb = new StringBuilder();
            BufferedReader br = new BufferedReader(new FileReader(f));
            try {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            } finally { try { br.close(); } catch (Exception ignore) {} }
            JSONArray arr = new JSONArray(sb.toString());
            for (int i = 0; i < arr.length(); i++) {
                String wxid = arr.optString(i, null);
                if (wxid != null && !wxid.isEmpty()) avatarNoUrlSet.add(wxid);
            }
            log("[noUrl] 黑名单加载完成，共 " + avatarNoUrlSet.size() + " 条");
        } catch (Exception e) {
            log("[noUrl] 加载黑名单失败: " + e.getMessage());
        }
    }
}

/**
 * 将当前内存黑名单持久化到磁盘（在后台线程调用）
 */
private void saveNoUrlCache() {
    synchronized (noUrlFileLock) {
        try {
            JSONArray arr = new JSONArray();
            for (String wxid : new java.util.ArrayList<String>(avatarNoUrlSet)) {
                arr.put(wxid);
            }
            File f = getNoUrlFile();
            java.io.FileWriter fw = new java.io.FileWriter(f);
            try { fw.write(arr.toString()); } finally { try { fw.close(); } catch (Exception ignore) {} }
        } catch (Exception e) {
            log("[noUrl] 保存黑名单失败: " + e.getMessage());
        }
    }
}

/**
 * 将单个wxid加入黑名单并异步持久化
 */
private void addToNoUrlCache(final String wxid) {
    avatarNoUrlSet.add(wxid);
    new Thread(new Runnable() {
        public void run() { saveNoUrlCache(); }
    }).start();
}

/**
 * 清空黑名单（runIncrementalSync调用，允许重新尝试下载）
 */
private void clearNoUrlCache() {
    avatarNoUrlSet.clear();
    new Thread(new Runnable() {
        public void run() {
            synchronized (noUrlFileLock) {
                try {
                    File f = getNoUrlFile();
                    if (f.exists()) f.delete();
                } catch (Exception ignore) {}
            }
        }
    }).start();
    log("[noUrl] 黑名单已清空，下次同步将重新尝试");
}

/**
 * 手动触发增量同步：清理孤立头像 + 补充新增联系人头像 + 检测URL变化
 * 由主页"🔄 同步"按钮调用，不在批量添加页面自动触发
 * onComplete：同步完成后在主线程回调（可为 null）
 */
private void runIncrementalSync(final Runnable onComplete) {
    new Thread(new Runnable() {
        public void run() {
            try {
                // 同步时清空黑名单，允许重新尝试（联系人头像URL可能已更新）
                clearNoUrlCache();
                // 强制下次同步后重新查询WCDB公众号/标签（可能有新增）
                synchronized (wcdbCacheLock) { officialCacheTime = 0; }
                // 强制刷新联系人列表（忽略5分钟缓存）
                synchronized (wcdbCacheLock) { friendListCacheTime = 0; }
                getCachedFriendList();
                // 同时刷新公众号列表
                loadWcdbOfficialAccounts(cachedFriendNameMap);

                final java.util.List<String> wxidSnapshot = new java.util.ArrayList<>();
                synchronized (wcdbCacheLock) {
                    if (cachedFriendWxidList  != null) wxidSnapshot.addAll(cachedFriendWxidList);
                    if (cachedOfficialWxids   != null) wxidSnapshot.addAll(cachedOfficialWxids);
                    if (cachedServiceWxids    != null) wxidSnapshot.addAll(cachedServiceWxids);
                    if (cachedEnterpriseWxids != null) wxidSnapshot.addAll(cachedEnterpriseWxids);
                }

                java.util.Set<String> currentSafeNames = new java.util.HashSet<>();
                for (String wxid : wxidSnapshot) {
                    currentSafeNames.add(
                        wxid.replace("@","_").replace(":","_").replace("/","_"));
                }
                File avatarDir = getAvatarCacheDir();
                File[] files = avatarDir.listFiles();
                int cleanCount = 0;
                if (files != null) {
                    for (File f : files) {
                        String fname = f.getName();
                        // 只清理 .tmp 临时文件，.json（如no_url.json）和其他文件保留
                        if (fname.endsWith(".tmp")) { f.delete(); continue; }
                        if (!fname.endsWith(".png")) continue; // 保留json等其他文件
                        String safeName = fname.substring(0, fname.length() - 4);
                        if (!currentSafeNames.contains(safeName)) {
                            f.delete();
                            cleanCount++;
                        }
                    }
                }
                log("孤立头像清理完成，共清理 " + cleanCount + " 个");

                java.util.List<String> toDownload = new java.util.ArrayList<>();
                int newCount = 0;
                for (String wxid : wxidSnapshot) {
                    if (wxid == null || wxid.isEmpty()) continue;
                    File diskFile = getAvatarFile(wxid);
                    if (!diskFile.exists()) {
                        if (avatarNoUrlSet.contains(wxid)) continue; // 黑名单已覆盖
                        toDownload.add(wxid);
                        submitAvatarTask(wxid, null);
                        newCount++;
                        Thread.sleep(20);
                    } else {
                        final String finalWxid = wxid;
                        new Thread(new Runnable() {
                            public void run() {
                                try {
                                    String latestUrl = getAvatarUrl(finalWxid, true);
                                    if (latestUrl == null || latestUrl.isEmpty()) return;
                                    String storedUrl = "";
                                    try { storedUrl = getString("avatar_url_" + finalWxid, ""); }
                                    catch (Exception ignore) {}
                                    if (!latestUrl.equals(storedUrl)) {
                                        log("头像URL变化，重新下载: " + finalWxid);
                                        submitAvatarTask(finalWxid, null);
                                    }
                                } catch (Exception e) {
                                    log("增量URL检测失败 " + finalWxid + ": " + e.getMessage());
                                }
                            }
                        }).start();
                    }
                }

                // 等待下载完成（最多10秒），对仍未有磁盘文件的加入黑名单
                // 解决 download() API 静默超时不触发 onFailure 回调的问题
                if (!toDownload.isEmpty()) {
                    Thread.sleep(10000);
                    int timeoutCount = 0;
                    for (String wxid : toDownload) {
                        if (!getAvatarFile(wxid).exists() && !avatarNoUrlSet.contains(wxid)) {
                            log("[noUrl] 同步下载超时，加入黑名单: " + wxid);
                            addToNoUrlCache(wxid);
                            timeoutCount++;
                        }
                    }
                    if (timeoutCount > 0) {
                        log("[noUrl] 同步超时黑名单新增 " + timeoutCount + " 条，当前共 " + avatarNoUrlSet.size() + " 条");
                    }
                }

                final int finalNew = newCount;
                final int finalClean = cleanCount;
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    public void run() {
                        uiToast("✅ 同步完成：新增 " + finalNew + " 个，清理 " + finalClean + " 个孤立头像");
                        if (onComplete != null) onComplete.run();
                    }
                });
            } catch (Exception e) {
                log("增量同步失败: " + e.getMessage());
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    public void run() { uiToast("❌ 同步失败: " + e.getMessage()); }
                });
            }
        }
    }).start();
}

/**
 * 统一头像加载方法：内存→磁盘→网络
 * ListView 复用时通过 avatarView.setTag(wxid) + decodeAndShow 里的 tag 核对防止错位
 */
private void loadAvatar(final ImageView avatarView, final String wxid) {
    avatarView.setTag(wxid);
    Bitmap cached = avatarBitmapCache.get(wxid);
    if (cached != null) { avatarView.setImageBitmap(cached); return; }
    File diskFile = getAvatarFile(wxid);
    if (diskFile.exists()) {
        new Thread(new Runnable() {
            public void run() { decodeAndShow(diskFile, wxid, avatarView); }
        }).start();
        return;
    }
    submitAvatarTask(wxid, avatarView);
}

/**
 * 后台预加载头像（无UI回调），仅填充缓存
 * 供打开主对话框时静默预热使用
 */
private void preloadAvatar(final String wxid) {
    submitAvatarTask(wxid, null);
}

/**
 * 批量后台预加载（内存→磁盘 补充，不触发网络）
 * avatarInitialLoadDone=true 时首次全量下载已完成，只做内存预热
 */
private void preloadAvatars(final java.util.List<String> wxidList) {
    if (wxidList == null) return;
    for (final String wxid : wxidList) {
        if (wxid == null || wxid.isEmpty()) continue;
        if (avatarBitmapCache.containsKey(wxid)) continue;
        // 只用磁盘文件填内存，不走网络
        File disk = getAvatarFile(wxid);
        if (disk.exists()) {
            final File f = disk;
            new Thread(new Runnable() {
                public void run() { decodeAndShow(f, wxid, null); }
            }).start();
        }
        // 没有磁盘文件的交由 submitAvatarTask 下载（onLoad 或增量维护负责）
    }
}

/**
 * 核心：在独立线程中获取头像URL，然后通过 download() 异步下载
 * download() 是异步API，直接在回调里处理后续逻辑，不阻塞线程
 * avatarView 为 null 时为纯后台预加载（不更新UI）
 * avatarLoadingSet 防止同一 wxid 同时发起多个下载
 * avatarPendingViews 收集下载进行中时注册的额外 View，下载完成后统一回调
 */
private void submitAvatarTask(final String wxid, final ImageView avatarView) {
    // 黑名单检查：之前确认无法获取URL的wxid直接跳过，不再调用WAuxiliary网络API
    if (avatarNoUrlSet.contains(wxid)) return;
    // Bug2 fix：contains+add 原子化，防止两线程同时通过 contains 检查
    synchronized (avatarLoadingSet) {
        if (avatarLoadingSet.contains(wxid)) {
            // 下载已在进行中：把新 View 加入 pending 列表，下载完后统一回调
            if (avatarView != null) {
                java.util.List<ImageView> views = avatarPendingViews.get(wxid);
                if (views == null) {
                    views = java.util.Collections.synchronizedList(new java.util.ArrayList<ImageView>());
                    avatarPendingViews.put(wxid, views);
                }
                views.add(avatarView);
            }
            return;
        }
        avatarLoadingSet.add(wxid);
    }

    new Thread(new Runnable() {
        public void run() {
            try {
                // 信号量限制并发（8个槽），适配3000+联系人场景
                avatarSemaphore.acquire();
            } catch (InterruptedException e) {
                avatarLoadingSet.remove(wxid);
                return;
            }
            try {
                String avatarUrl = getAvatarUrl(wxid, true);
                if (avatarUrl == null || avatarUrl.isEmpty()) {
                    // URL获取不到：加入黑名单，后续跳过，避免反复调用WAuxiliary API
                    addToNoUrlCache(wxid);
                    log("[noUrl] 无法获取头像URL，加入黑名单: " + wxid);
                    avatarLoadingSet.remove(wxid);
                    avatarSemaphore.release();
                    return;
                }

                File diskFile = getAvatarFile(wxid);
                // 读取上次缓存的URL，判断是否需要重新下载
                String storedUrl = "";
                try { storedUrl = getString("avatar_url_" + wxid, ""); } catch (Exception e) { log("读取头像URL缓存失败 " + wxid + ": " + e.getMessage()); }

                boolean needDownload = !diskFile.exists() || !avatarUrl.equals(storedUrl);

                if (!needDownload) {
                    // URL未变化且磁盘文件存在，直接解码显示
                    decodeAndShow(diskFile, wxid, avatarView);
                    avatarLoadingSet.remove(wxid);
                    avatarSemaphore.release();
                    return;
                }

                // 需要下载：download() 是异步的，在回调里完成后续处理
                // 用 AtomicBoolean 防止 download() 静默超时时与正常回调双重释放信号量
                final java.util.concurrent.atomic.AtomicBoolean downloadDone =
                    new java.util.concurrent.atomic.AtomicBoolean(false);
                final String finalUrl = avatarUrl;
                File tmpFile = new File(getAvatarCacheDir(), wxid.replace("@","_").replace(":","_").replace("/","_") + ".tmp");
                download(finalUrl, tmpFile.getAbsolutePath(), null,
                    new me.hd.wauxv.plugin.api.callback.PluginCallBack.DownloadCallback() {
                        public void onSuccess(File file) {
                            if (!downloadDone.compareAndSet(false, true)) return; // 超时已处理
                            try {
                                if (diskFile.exists()) diskFile.delete();
                                file.renameTo(diskFile);
                                try { putString("avatar_url_" + wxid, finalUrl); } catch (Exception ignore) {}
                                decodeAndShow(diskFile, wxid, avatarView);
                            } catch (Exception e) {
                                log("头像保存失败 " + wxid + ": " + e.getMessage());
                            } finally {
                                avatarLoadingSet.remove(wxid);
                                avatarSemaphore.release();
                            }
                        }
                        public void onFailure(String error) {
                            if (!downloadDone.compareAndSet(false, true)) return; // 超时已处理
                            log("头像下载失败，加入黑名单 " + wxid + ": " + error);
                            addToNoUrlCache(wxid);
                            avatarLoadingSet.remove(wxid);
                            avatarSemaphore.release();
                        }
                    });
                // 超时监控线程：download()有时静默超时不触发任何回调，15秒后强制处理
                new Thread(new Runnable() {
                    public void run() {
                        try {
                            Thread.sleep(5000);
                            if (downloadDone.compareAndSet(false, true)) {
                                // 5秒内无任何回调：视为失败，加入黑名单
                                log("[noUrl] download超时无回调，加入黑名单: " + wxid);
                                addToNoUrlCache(wxid);
                                avatarLoadingSet.remove(wxid);
                                avatarSemaphore.release();
                            }
                        } catch (InterruptedException ignore) {}
                    }
                }).start();

            } catch (Throwable e) {
                log("头像加载异常 " + wxid + ": " + e.getMessage());
                avatarLoadingSet.remove(wxid);
                avatarSemaphore.release();
            }
        }
    }).start();
}

/**
 * 向内存缓存存入 Bitmap，超过 AVATAR_CACHE_MAX 时删除最旧的一半
 * Bug3 fix：eviction 整块 synchronized(avatarBitmapCache)，防止多线程迭代冲突
 */
private void putAvatarBitmap(String wxid, Bitmap bitmap) {
    synchronized (avatarBitmapCache) {
        avatarBitmapCache.put(wxid, bitmap);
        if (avatarBitmapCache.size() > AVATAR_CACHE_MAX) {
            int removeCount = AVATAR_CACHE_MAX / 2;
            java.util.Iterator<String> it = avatarBitmapCache.keySet().iterator();
            while (it.hasNext() && removeCount > 0) {
                it.next();
                it.remove();
                removeCount--;
            }
        }
    }
}

/**
 * 解码磁盘文件到内存缓存，有UI目标则更新ImageView
 * Bug1 fix：tag 核对改为 tag != null && equals，tag 为 null 时拒绝写入（未知 View 状态）
 * Bug2 fix：下载完成后统一回调 avatarPendingViews 中所有等待的 View
 */
private void decodeAndShow(File diskFile, final String wxid, ImageView avatarView) {
    try {
        final Bitmap bitmap = BitmapFactory.decodeFile(diskFile.getAbsolutePath());
        if (bitmap == null) return;

        putAvatarBitmap(wxid, bitmap);

        // 收集需要更新的 View：主 View + 所有 pending View
        final java.util.List<ImageView> targets = new java.util.ArrayList<ImageView>();
        if (avatarView != null) targets.add(avatarView);
        java.util.List<ImageView> pending = avatarPendingViews.remove(wxid);
        if (pending != null) {
            synchronized (pending) { targets.addAll(pending); }
        }

        if (targets.isEmpty()) return;

        new Handler(Looper.getMainLooper()).post(new Runnable() {
            public void run() {
                for (int _i = 0; _i < targets.size(); _i++) {
                    ImageView iv = targets.get(_i);
                    if (iv == null) continue;
                    // 双重保护：
                    // 1. tag 核对：确认 View 仍绑定到同一 wxid
                    // 2. getParent() != null：View 被 removeAllViews 摘除后 parent 立即变 null，
                    //    此时跳过写入；不用 isAttachedToWindow()，因为预渲染容器尚未 attach 到窗口
                    Object tag = iv.getTag();
                    if (tag == null || !wxid.equals(tag.toString())) continue;
                    if (iv.getParent() == null) continue;
                    iv.setImageBitmap(bitmap);
                }
            }
        });
    } catch (Exception e) {
        log("头像解码失败 " + wxid + ": " + e.getMessage());
    }
}

/**
 * 显示UI提示
 */
void uiToast(String s) {
    try {
        toast(s);
    } catch (Throwable ignore) {
    }
}

/**
 * 动态构建分组数据目录，自动适配当前微信用户（0/999等多开场景）
 * cacheDir 内置变量示例：/storage/emulated/{userId}/Android/media/com.tencent.mm/WAuxiliary/Cache
 * getParentFile() 得到 WAuxiliary 根目录，再拼 Resource/Group
 */
private File getGroupDir() {
    return new File(new File(cacheDir).getParentFile(), "Resource/Group");
}

/**
 * 密度转换
 */
int dp(int v) {
    try {
        Activity a = getTopActivity();
        if (a != null) {
            float d = a.getResources().getDisplayMetrics().density;
            return (int) (v * d + 0.5f);
        }
    } catch (Throwable e) {
    }
    return v;
}

/**
 * fix⑤: 统一文件保存方法，使用 try-finally 防止句柄泄漏（兼容 JS-like 环境）
 */
private void saveGroupData(JSONArray groupItems, File groupFile) {
    FileWriter writer = null;
    try {
        writer = new FileWriter(groupFile);
        writer.write(groupItems.toString(2));
    } catch (Exception e) {
        log("保存分组数据失败: " + e.getMessage());
        uiToast("❌ 保存失败");
    } finally {
        if (writer != null) { try { writer.close(); } catch (Exception ignore) {} }
    }
}

/**
 * 排序分组列表（按order值升序）
 * 返回的List元素与groupItems中的JSONObject是同一引用，修改会同步到groupItems
 */
private java.util.List<JSONObject> getSortedGroups(JSONArray groupItems) {
    java.util.List<JSONObject> sortedGroups = new java.util.ArrayList<>();
    for (int i = 0; i < groupItems.length(); i++) {
        sortedGroups.add(groupItems.getJSONObject(i));
    }
    java.util.Collections.sort(sortedGroups, new java.util.Comparator<JSONObject>() {
        public int compare(JSONObject a, JSONObject b) {
            try {
                return Integer.compare(a.getInt("order"), b.getInt("order"));
            } catch (Exception e) {
                return 0;
            }
        }
    });
    return sortedGroups;
}

/**
 * 显示分组选择对话框
 */
void showGroupSelectionDialog(JSONArray groupItems, String currentWxid, File groupFile) {
    applyTheme();
    Activity act = getTopActivity();
    if (act == null) {
        uiToast("无法获取当前Activity");
        return;
    }

    isDialogShowing = true;

    new Handler(Looper.getMainLooper()).post(new Runnable() {
        public void run() {
            try {
                Object[] _spec = buildStandardDialog(act, "选择分组");
                final Dialog d = (Dialog) _spec[0];
                LinearLayout root = (LinearLayout) _spec[1];
                int width = ((Integer) _spec[2]).intValue();

                TextView info = new TextView(act);
                info.setText("请选择要将该联系人添加到的分组");
                styleTextSecondary(info);
                info.setPadding(0, 0, 0, dp(16));
                root.addView(info);

                LinearLayout wxidLayout = new LinearLayout(act);
                wxidLayout.setOrientation(LinearLayout.HORIZONTAL);
                wxidLayout.setPadding(0, 0, 0, dp(20));

                TextView wxidText = new TextView(act);
                wxidText.setText("联系人wxid: " + currentWxid);
                styleTextSecondary(wxidText);
                wxidText.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));
                wxidLayout.addView(wxidText);

                Button addGroupBtn = btn(act, "添加分组");
                addGroupBtn.setTextSize(12);
                addGroupBtn.setPadding(dp(12), dp(6), dp(12), dp(6));
                addGroupBtn.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        showCreateNewGroupDialog(groupItems, currentWxid, groupFile, d);
                    }
                });
                wxidLayout.addView(addGroupBtn);

                // 🔄 同步按钮：手动触发增量检测（清理孤立头像 + 新增下载 + URL变化）
                Button syncBtn = btnSmall(act, "🔄 同步");
                LinearLayout.LayoutParams syncLP = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                syncLP.leftMargin = dp(8);
                syncBtn.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        syncBtn.setEnabled(false);
                        syncBtn.setText("同步中...");
                        runIncrementalSync(new Runnable() {
                            public void run() {
                                syncBtn.setEnabled(true);
                                syncBtn.setText("🔄 同步");
                            }
                        });
                    }
                });
                wxidLayout.addView(syncBtn, syncLP);
                root.addView(wxidLayout);

                ScrollView scrollView = new ScrollView(act);
                scrollView.setBackground(shapeStrokeInt(CI_CARD_BG, dp(12), CI_CARD_STROKE));
                scrollView.setPadding(dp(16), dp(12), dp(16), dp(12));

                LinearLayout groupContainer = new LinearLayout(act);
                groupContainer.setOrientation(LinearLayout.VERTICAL);
                groupContainer.setId(ID_GROUP_CONTAINER);
                scrollView.addView(groupContainer);
                root.addView(scrollView);

                // fix①: 上移/下移使用sortedGroups的对象引用操作，而非用sortedIndex访问groupItems
                Runnable refreshGroupListUI = new Runnable() {
                    public void run() {
                        groupContainer.removeAllViews();
                        // getSortedGroups返回的元素与groupItems共享引用，修改order会同步
                        java.util.List<JSONObject> sortedGroups = getSortedGroups(groupItems);

                        for (int i = 0; i < sortedGroups.size(); i++) {
                            try {
                                JSONObject group = sortedGroups.get(i);
                                String groupTitle = group.getString("title");
                                JSONArray idList = group.getJSONArray("idList");

                                LinearLayout groupItem = new LinearLayout(act);
                                groupItem.setOrientation(LinearLayout.HORIZONTAL);
                                groupItem.setPadding(dp(16), dp(12), dp(16), dp(12));
                                groupItem.setBackground(shapeStrokeInt(CI_CARD_BG, dp(8), CI_DIVIDER));
                                LinearLayout.LayoutParams itemParams = new LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                                itemParams.bottomMargin = dp(8);
                                groupItem.setLayoutParams(itemParams);

                                LinearLayout infoLayout = new LinearLayout(act);
                                infoLayout.setOrientation(LinearLayout.VERTICAL);
                                infoLayout.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));

                                TextView groupTitleView = new TextView(act);
                                groupTitleView.setText(groupTitle);
                                groupTitleView.setTextSize(16);
                                styleTextPrimary(groupTitleView);
                                infoLayout.addView(groupTitleView);

                                TextView memberCount = new TextView(act);
                                memberCount.setText("成员数量: " + idList.length());
                                memberCount.setTextSize(12);
                                styleTextSecondary(memberCount);
                                infoLayout.addView(memberCount);
                                groupItem.addView(infoLayout);

                                LinearLayout moveLayout = new LinearLayout(act);
                                moveLayout.setOrientation(LinearLayout.HORIZONTAL);
                                moveLayout.setLayoutParams(new LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

                                Button moveUpBtn = btnSmall(act, "↑");
                                Button moveDownBtn = btnSmall(act, "↓");

                                final int groupIndex = i;
                                final String groupTitleStr = groupTitle;
                                final JSONArray groupIdList = idList;
                                // fix①: 捕获sortedGroups引用用于上移/下移
                                final java.util.List<JSONObject> capturedSortedGroups = sortedGroups;

                                moveUpBtn.setOnClickListener(new View.OnClickListener() {
                                    public void onClick(View v) {
                                        if (groupIndex > 0) {
                                            try {
                                                // fix①: 通过sortedGroups引用操作，与groupItems共享同一对象
                                                JSONObject curGroup = capturedSortedGroups.get(groupIndex);
                                                JSONObject prevGroup = capturedSortedGroups.get(groupIndex - 1);
                                                int curOrder = curGroup.getInt("order");
                                                int prevOrder = prevGroup.getInt("order");
                                                curGroup.put("order", prevOrder);
                                                prevGroup.put("order", curOrder);
                                                saveGroupData(groupItems, groupFile);
                                                uiToast("✅ 分组已上移");
                                                refreshGroupListUI.run();
                                            } catch (Exception e) {
                                                log("上移分组失败: " + e.getMessage());
                                                uiToast("❌ 上移失败");
                                            }
                                        } else {
                                            uiToast("⚠️ 已经是第一个分组");
                                        }
                                    }
                                });

                                moveDownBtn.setOnClickListener(new View.OnClickListener() {
                                    public void onClick(View v) {
                                        if (groupIndex < capturedSortedGroups.size() - 1) {
                                            try {
                                                // fix①: 通过sortedGroups引用操作
                                                JSONObject curGroup = capturedSortedGroups.get(groupIndex);
                                                JSONObject nextGroup = capturedSortedGroups.get(groupIndex + 1);
                                                int curOrder = curGroup.getInt("order");
                                                int nextOrder = nextGroup.getInt("order");
                                                curGroup.put("order", nextOrder);
                                                nextGroup.put("order", curOrder);
                                                saveGroupData(groupItems, groupFile);
                                                uiToast("✅ 分组已下移");
                                                refreshGroupListUI.run();
                                            } catch (Exception e) {
                                                log("下移分组失败: " + e.getMessage());
                                                uiToast("❌ 下移失败");
                                            }
                                        } else {
                                            uiToast("⚠️ 已经是最后一个分组");
                                        }
                                    }
                                });

                                moveLayout.addView(moveUpBtn);
                                moveLayout.addView(moveDownBtn);
                                groupItem.addView(moveLayout);

                                Button selectBtn = btn(act, "选择");
                                selectBtn.setTextSize(12);
                                selectBtn.setPadding(dp(12), dp(6), dp(12), dp(6));
                                selectBtn.setOnClickListener(new View.OnClickListener() {
                                    public void onClick(View v) {
                                        handleGroupSelection(groupTitleStr, groupIdList, currentWxid, groupItems, groupFile);
                                        d.dismiss();
                                        isDialogShowing = false;
                                    }
                                });
                                groupItem.addView(selectBtn);

                                groupItem.setOnClickListener(new View.OnClickListener() {
                                    public void onClick(View v) {
                                        d.dismiss();
                                        isDialogShowing = false;
                                        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                                            public void run() {
                                                try {
                                                // 按title在groupItems中查找最新索引
                                                int latestIndex = -1;
                                                for (int idx = 0; idx < groupItems.length(); idx++) {
                                                    try {
                                                        if (groupItems.getJSONObject(idx).getString("title").equals(groupTitleStr)) {
                                                            latestIndex = idx;
                                                            break;
                                                        }
                                                    } catch (Exception ignore) {}
                                                }
                                                if (latestIndex != -1) {
                                                    showGroupDetailDialog(latestIndex, groupItems, groupFile, currentWxid);
                                                } else {
                                                    uiToast("分组索引查找失败");
                                                }
                                                } catch (Throwable e) {
                                                    log("打开分组详情失败: " + e.getMessage());
                                                    uiToast("❌ 打开分组详情失败");
                                                }
                                            }
                                        }, 100);
                                    }
                                });

                                groupContainer.addView(groupItem);
                            } catch (Exception e) {
                                log("显示分组失败: " + e.getMessage());
                            }
                        }
                    }
                };

                refreshGroupListUI.run();

                Button cancelBtn = btn(act, "取消");
                LinearLayout.LayoutParams lpCancel = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                lpCancel.topMargin = dp(20);
                root.addView(cancelBtn, lpCancel);

                cancelBtn.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        d.dismiss();
                        isDialogShowing = false;
                    }
                });

                d.setOnCancelListener(new DialogInterface.OnCancelListener() {
                    public void onCancel(DialogInterface dialog) { isDialogShowing = false; }
                });
                d.setOnDismissListener(new DialogInterface.OnDismissListener() {
                    public void onDismiss(DialogInterface dialog) { isDialogShowing = false; }
                });
                showDialogWrap(_spec);

            } catch (Throwable e) {
                isDialogShowing = false;
                log("创建对话框失败: " + e.getMessage());
                uiToast("创建对话框失败");
            }
        }
    });
}

/**
 * 从 GroupInfo 对象中提取 roomId（群wxid）和群名
 * 使用 getRoomId() / getName() 方法调用，能正确获取
 * 返回 [roomId, name]
 */
private String[] extractGroupInfo(Object groupInfo) {
    String roomId = null;
    String name = null;
    try {
        roomId = (String) groupInfo.getClass().getMethod("getRoomId").invoke(groupInfo);
    } catch (Exception e) {
        log("GroupInfo.getRoomId() 失败: " + e.getMessage());
    }
    try {
        name = (String) groupInfo.getClass().getMethod("getName").invoke(groupInfo);
    } catch (Exception e) {
        try {
            name = (String) groupInfo.getClass().getMethod("getRemark").invoke(groupInfo);
        } catch (Exception ignore) {}
    }
    // BeanShell 对 new String[]{a,b} 初始化器有兼容性问题，用显式方式
    String[] result = new String[2];
    result[0] = roomId;
    result[1] = name;
    return result;
}

/**
 * 获取缓存的联系人列表（好友 + 群聊），增加昵称完整加载标志管理
 */
private void getCachedFriendList() {
    synchronized (wcdbCacheLock) {
    long currentTime = System.currentTimeMillis();
    if (cachedFriendWxidList != null &&
        cachedFriendNameMap != null &&
        (currentTime - friendListCacheTime) < CACHE_DURATION) {
        return; // 缓存有效，直接使用
    }

    // 缓存过期，重新获取，同时重置昵称加载标志
    cachedFriendWxidList = new java.util.ArrayList<>();
    cachedFriendNameMap = new java.util.HashMap<>();
    // 获取好友列表
    try {
        Object friendListObj = getFriendList();
        log("getFriendList 返回类型: " + (friendListObj == null ? "null" : friendListObj.getClass().getName()));
        if (friendListObj != null && friendListObj instanceof java.util.List) {
            java.util.List<?> friendList = (java.util.List<?>) friendListObj;
            log("好友列表原始数量: " + friendList.size());
            int skipCount = 0;
            for (Object friend : friendList) {
                try {
                    Class cls = friend.getClass();
                    String wxid = (String) cls.getMethod("getWxid").invoke(friend);
                    if (wxid == null || wxid.isEmpty()) { skipCount++; continue; }
                    if (wxid.endsWith("@chatroom")) { skipCount++; continue; } // 群聊不放入好友段
                    String nickname = "";
                    try { nickname = (String) cls.getMethod("getNickname").invoke(friend); } catch (Exception ignore) {}
                    if (nickname == null || nickname.isEmpty()) {
                        try { nickname = (String) cls.getMethod("getRemark").invoke(friend); } catch (Exception ignore) {}
                    }
                    cachedFriendWxidList.add(wxid);
                    cachedFriendNameMap.put(wxid, nickname != null ? nickname : "");
                } catch (Exception e) {
                    log("处理好友对象失败: " + e.getMessage() + " 类型=" + friend.getClass().getSimpleName());
                }
            }
            log("好友加载完成: " + cachedFriendWxidList.size() + " 个，跳过: " + skipCount);
        } else {
            log("getFriendList 返回空或非List: " + friendListObj);
        }
    } catch (Exception e) {
        log("获取好友列表失败: " + e.getMessage());
    }

    // 获取群聊列表并合并（去重）
    try {
        Object groupListObj = getGroupList();
        log("getGroupList 返回类型: " + (groupListObj == null ? "null" : groupListObj.getClass().getName()));
        if (groupListObj != null && groupListObj instanceof java.util.List) {
            java.util.List<?> groupList = (java.util.List<?>) groupListObj;
            log("群聊列表原始数量: " + groupList.size());
            int addedGroup = 0;
            for (Object group : groupList) {
                try {
                    String[] info = extractGroupInfo(group);
                    String roomId = info[0];
                    String groupName = info[1];
                    if (roomId != null && !roomId.isEmpty() && !cachedFriendNameMap.containsKey(roomId)) {
                        cachedFriendWxidList.add(roomId);
                        cachedFriendNameMap.put(roomId, groupName != null ? groupName : "");
                        addedGroup++;
                    }
                } catch (Exception e) {
                    log("处理群聊对象失败: " + e.getMessage() + " 类型=" + group.getClass().getSimpleName());
                }
            }
            log("群聊加载完成: " + addedGroup + " 个");
        } else {
            log("getGroupList 返回空或非List: " + groupListObj);
        }
    } catch (Exception e) {
        log("获取群聊列表失败: " + e.getMessage());
    }

    friendListCacheTime = currentTime;
    } // end synchronized (wcdbCacheLock)
    // 在后台线程一次性构建搜索索引（toLowerCase耗时，不在主/UI线程做）
    final java.util.List<String> wxidSnap = new java.util.ArrayList<>(cachedFriendWxidList);
    final java.util.Map<String, String> nameSnap = new java.util.HashMap<>(cachedFriendNameMap);
    new Thread(new Runnable() {
        public void run() {
            java.util.Map<String, String> idx = new java.util.HashMap<>();
            for (String wxid : wxidSnap) {
                String name = nameSnap.get(wxid);
                if (name == null) name = "";
                idx.put(wxid, (name + " " + wxid).toLowerCase());
            }
            cachedSearchIndex = idx;
            log("搜索索引构建完成，共 " + idx.size() + " 条");
        }
    }).start();
}

/**
 * 显示批量添加联系人对话框
 */
// ============================================================
// WCDB 工具：获取 EnMicroMsg.db 已打开实例（同进程内直接复用，无需密钥）
// ============================================================
// ==================== WCDB 工具层 ====================

/**
 * 获取 EnMicroMsg.db 已打开的 WCDB 实例（同进程内直接复用，无需密钥）
 * 优先选择文件最大的实例（排除 WAL/SHM 临时文件）
 */
private Object getWcdbInstance() {
    try {
        Class cls = Class.forName("com.tencent.wcdb.database.SQLiteDatabase",
            false, hostContext.getClassLoader());
        java.lang.reflect.Field f = cls.getDeclaredField("sActiveDatabases");
        f.setAccessible(true);
        java.util.WeakHashMap map = (java.util.WeakHashMap) f.get(null);
        java.lang.reflect.Method getPath = cls.getMethod("getPath");
        Object best = null; long bestSize = 0;
        for (Object o : new java.util.ArrayList(map.keySet())) {
            if (o == null) continue;
            String path = (String) getPath.invoke(o);
            if (path != null && path.contains("EnMicroMsg.db")
                    && !path.endsWith("-wal") && !path.endsWith("-shm")) {
                long sz = new java.io.File(path).length();
                if (sz > bestSize) { bestSize = sz; best = o; }
            }
        }
        return best;
    } catch (Exception e) {
        log("[wcdb] getWcdbInstance 失败: " + e.getMessage());
        return null;
    }
}

/** 执行原始 SQL，返回 Cursor 对象（失败返回 null） */
private Object wcdbRawQuery(Object db, String sql) {
    try {
        java.lang.reflect.Method rq = db.getClass().getDeclaredMethod(
            "rawQuery", String.class, Object[].class);
        rq.setAccessible(true);
        return rq.invoke(db, sql, null);
    } catch (Exception e) {
        log("[wcdb] rawQuery 失败: " + e.getMessage());
        return null;
    }
}


/** 从 WCDB Cursor 读取 col0=username、col1=nickname，写入 wxidList 和 nameMap */
private void readWcdbCursor(Object cursor, java.util.List<String> wxidList,
                             java.util.Map<String, String> nameMap) {
    if (cursor == null) return;
    try {
        Class cc = cursor.getClass();
        java.lang.reflect.Method mn = cc.getMethod("moveToNext");
        java.lang.reflect.Method gs = cc.getMethod("getString", int.class);
        java.lang.reflect.Method cl = cc.getMethod("close");
        try {
            while ((Boolean) mn.invoke(cursor)) {
                String wxid = (String) gs.invoke(cursor, 0);
                String nick  = (String) gs.invoke(cursor, 1);
                if (wxid == null || wxid.isEmpty()) continue;
                wxidList.add(wxid);
                nameMap.put(wxid, nick != null ? nick : wxid);
            }
        } finally { cl.invoke(cursor); }
    } catch (Exception ignore) {}
}

private void loadWcdbOfficialAccounts(java.util.Map<String, String> nameMap) {
    synchronized (wcdbCacheLock) {
    long _now = System.currentTimeMillis();
    if (cachedOfficialWxids != null && cachedServiceWxids != null
            && cachedEnterpriseWxids != null
            && (_now - officialCacheTime) < CACHE_DURATION) {
        return;
    }
    cachedOfficialWxids   = new java.util.ArrayList<>();
    cachedServiceWxids    = new java.util.ArrayList<>();
    cachedEnterpriseWxids = new java.util.ArrayList<>();
    try {
        Object db = getWcdbInstance();
        if (db == null) { log("[wcdb] 公众号加载：DB实例为null"); return; }

        // 订阅号/服务号：verifyFlag bit3=1，bit4区分类型
        Object cursor = wcdbRawQuery(db,
            "SELECT username, nickname, verifyFlag FROM rcontact " +
            "WHERE (username LIKE 'gh_%' OR username = 'weixin') " +
            "  AND (verifyFlag & 8) != 0" +
            "  AND (type & 1) != 0 " +
            " ORDER BY nickname");
        if (cursor != null) {
            try {
                Class cc = cursor.getClass();
                java.lang.reflect.Method mn = cc.getMethod("moveToNext");
                java.lang.reflect.Method gs = cc.getMethod("getString", int.class);
                java.lang.reflect.Method gi = cc.getMethod("getInt", int.class);
                java.lang.reflect.Method cl = cc.getMethod("close");
                try {
                    while ((Boolean) mn.invoke(cursor)) {
                        String wxid = (String) gs.invoke(cursor, 0);
                        String nick = (String) gs.invoke(cursor, 1);
                        int    flag = (Integer) gi.invoke(cursor, 2);
                        if (wxid == null || wxid.isEmpty()) continue;
                        nameMap.put(wxid, (nick != null && !nick.isEmpty()) ? nick : wxid);
                        if ((flag & 16) != 0) cachedServiceWxids.add(wxid);
                        else                  cachedOfficialWxids.add(wxid);
                    }
                } finally { cl.invoke(cursor); }
            } catch (Exception ignore) {}
        }

        // 企业好友：wxid 以 ww_ / wxwork_ 开头，或以 @openim 结尾
        Object ecursor = wcdbRawQuery(db,
            "SELECT username, nickname FROM rcontact " +
            "WHERE (username LIKE 'ww_%' OR username LIKE 'wxwork_%' OR username LIKE '%@openim')" +
            "  AND (type & 1) != 0 ORDER BY nickname");
        if (ecursor != null) {
            try {
                Class cc = ecursor.getClass();
                java.lang.reflect.Method mn = cc.getMethod("moveToNext");
                java.lang.reflect.Method gs = cc.getMethod("getString", int.class);
                java.lang.reflect.Method cl = cc.getMethod("close");
                try {
                    while ((Boolean) mn.invoke(ecursor)) {
                        String wxid = (String) gs.invoke(ecursor, 0);
                        String nick = (String) gs.invoke(ecursor, 1);
                        if (wxid == null || wxid.isEmpty()) continue;
                        nameMap.put(wxid, (nick != null && !nick.isEmpty()) ? nick : wxid);
                        cachedEnterpriseWxids.add(wxid);
                    }
                } finally { cl.invoke(ecursor); }
            } catch (Exception ignore) {}
        }

        officialCacheTime = System.currentTimeMillis();
        log("[wcdb] 订阅号=" + cachedOfficialWxids.size()
            + " 服务号=" + cachedServiceWxids.size()
            + " 企业好友=" + cachedEnterpriseWxids.size());
    } catch (Exception e) {
        log("[wcdb] loadWcdbOfficialAccounts 失败: " + e.getMessage());
    }
    }
}

/**
 * 从 WCDB 加载标签定义及各标签成员
 * 成员列表只保留 availableFriends 中存在的 wxid（跨 Tab 可见性过滤）
 */
/**
 * 从 WCDB 加载标签定义及各标签成员。
 *
 * 优化前：N+2 次 SQL（PRAGMA + ContactLabel + 每标签一次 LIKE 全表扫）
 *   每个 LIKE 扫整张 rcontact，N 个标签 = N 次 O(rows) 扫描，极慢。
 *
 * 优化后：固定 3 次 SQL，在 Java 内存里完成分组：
 *   1. PRAGMA table_info(rcontact)  → 找 labelIds 字段名（版本间字段名不固定）
 *   2. SELECT FROM ContactLabel     → 全量标签定义（id, name），用 LinkedHashMap 保序
 *   3. SELECT username, labelField FROM rcontact
 *      WHERE labelField IS NOT NULL AND labelField != ''
 *      → 一次全表扫，只读有标签的行；
 *        Java 里按逗号分割 labelIds 字串，构建 Map<labelId, List<wxid>> 反向索引
 *
 * 只保留 availableFriends 中的 wxid（过滤出对话框可见的联系人）。
 */
private void loadWcdbLabels(java.util.List<String> availableFriends) {
    synchronized (wcdbCacheLock) {
    // 缓存有效则直接使用（与loadWcdbOfficialAccounts共享officialCacheTime）
    long _nowL = System.currentTimeMillis();
    if (cachedLabels != null && (_nowL - officialCacheTime) < CACHE_DURATION) {
        return;
    }
    cachedLabels       = new java.util.ArrayList<>();
    cachedLabelMembers = new java.util.HashMap<>();
    try {
        Object db = getWcdbInstance();
        if (db == null) { log("[wcdb] 标签加载：DB实例为null"); return; }

        // ① 找 rcontact 中含 "label" 的字段名（微信版本间字段名不固定）
        String labelIdsField = null;
        Object pragma = wcdbRawQuery(db, "PRAGMA table_info(rcontact)");
        if (pragma != null) {
            try {
                Class cc = pragma.getClass();
                java.lang.reflect.Method mn = cc.getMethod("moveToNext");
                java.lang.reflect.Method gs = cc.getMethod("getString", int.class);
                java.lang.reflect.Method gi = cc.getMethod("getColumnIndex", String.class);
                java.lang.reflect.Method cl = cc.getMethod("close");
                int nc = (Integer) gi.invoke(pragma, "name");
                try {
                    while ((Boolean) mn.invoke(pragma)) {
                        String col = (String) gs.invoke(pragma, nc);
                        if (col != null && col.toLowerCase().contains("label"))
                            labelIdsField = col;
                    }
                } finally { cl.invoke(pragma); }
            } catch (Exception ignore) {}
        }
        if (labelIdsField == null) { log("[wcdb] 未找到 rcontact 标签字段"); return; }

        // ② 全量标签定义：用 LinkedHashMap 保持 ContactLabel 表的原始顺序
        java.util.Map<String, String> labelNameMap = new java.util.LinkedHashMap<>();
        Object labelsCur = wcdbRawQuery(db,
            "SELECT labelID, labelName FROM ContactLabel ORDER BY labelID");
        if (labelsCur == null) return;
        try {
            Class cc  = labelsCur.getClass();
            java.lang.reflect.Method mn  = cc.getMethod("moveToNext");
            java.lang.reflect.Method gs0 = cc.getMethod("getString", int.class);
            java.lang.reflect.Method cl  = cc.getMethod("close");
            try {
                while ((Boolean) mn.invoke(labelsCur)) {
                    String lid   = (String) gs0.invoke(labelsCur, 0);
                    String lname = (String) gs0.invoke(labelsCur, 1);
                    if (lid != null) labelNameMap.put(lid, lname != null ? lname : "");
                }
            } finally { cl.invoke(labelsCur); }
        } catch (Exception ignore) {}
        if (labelNameMap.isEmpty()) { log("[wcdb] ContactLabel 表为空"); return; }

        // ③ 一次全表扫 rcontact：只读有标签的行（labelField 非空），
        //    在 Java 内存里按逗号分割 labelIds，构建反向索引 Map<labelId, List<wxid>>
        java.util.Set<String> friendSet = new java.util.HashSet<>(availableFriends);
        java.util.Map<String, java.util.List<String>> tempMap = new java.util.HashMap<>();

        Object rcontactCur = wcdbRawQuery(db,
            "SELECT username, " + labelIdsField + " FROM rcontact " +
            "WHERE " + labelIdsField + " IS NOT NULL AND " + labelIdsField + " != ''");
        if (rcontactCur != null) {
            try {
                Class cc = rcontactCur.getClass();
                java.lang.reflect.Method mn = cc.getMethod("moveToNext");
                java.lang.reflect.Method gs = cc.getMethod("getString", int.class);
                java.lang.reflect.Method cl = cc.getMethod("close");
                try {
                    while ((Boolean) mn.invoke(rcontactCur)) {
                        String wxid   = (String) gs.invoke(rcontactCur, 0);
                        String idsStr = (String) gs.invoke(rcontactCur, 1);
                        if (wxid == null || wxid.isEmpty()) continue;
                        if (idsStr == null || idsStr.isEmpty()) continue;
                        if (!friendSet.contains(wxid)) continue; // 只保留可见联系人
                        // labelIds 字段格式可能是 "1,3,7" 或 ",1,3," 等，split 统一处理
                        String[] ids = idsStr.split(",");
                        for (int k = 0; k < ids.length; k++) {
                            String lid = ids[k].trim();
                            if (lid.isEmpty()) continue;
                            java.util.List<String> bucket = (java.util.List<String>) tempMap.get(lid);
                            if (bucket == null) {
                                bucket = new java.util.ArrayList<>();
                                tempMap.put(lid, bucket);
                            }
                            bucket.add(wxid);
                        }
                    }
                } finally { cl.invoke(rcontactCur); }
            } catch (Exception ignore) {}
        }

        // ④ 按 ContactLabel 顺序组装最终结果（保证 Tab 标签顺序与微信一致）
        java.util.Iterator<java.util.Map.Entry<String, String>> it = labelNameMap.entrySet().iterator();
        while (it.hasNext()) {
            java.util.Map.Entry<String, String> entry = it.next();
            String lid   = entry.getKey();
            String lname = entry.getValue();
            cachedLabels.add(new String[]{lid, lname});
            java.util.List<String> members = (java.util.List<String>) tempMap.get(lid);
            cachedLabelMembers.put(lid, members != null ? members : new java.util.ArrayList<>());
        }
        log("[wcdb] 标签数=" + cachedLabels.size() + "，有成员标签数=" + tempMap.size());
    } catch (Exception e) {
        log("[wcdb] loadWcdbLabels 失败: " + e.getMessage());
    }
    } // end synchronized (wcdbCacheLock)
}


void showBatchAddFriendsDialog(int groupIndex, JSONArray groupItems, File groupFile, String currentWxid) {
    applyTheme();
    Activity act = getTopActivity();
    if (act == null) {
        uiToast("无法获取当前Activity");
        return;
    }
    isDialogShowing = true;
    uiToast("正在加载联系人列表...");

    new Thread(new Runnable() {
        public void run() {
            try {
                final JSONObject currentGroup = groupItems.getJSONObject(groupIndex);
                final JSONArray  currentIdList = currentGroup.getJSONArray("idList");

                getCachedFriendList();
                loadWcdbOfficialAccounts(cachedFriendNameMap);

                boolean noFriends  = cachedFriendWxidList == null || cachedFriendWxidList.isEmpty();
                boolean noOfficial = (cachedOfficialWxids == null || cachedOfficialWxids.isEmpty())
                                  && (cachedServiceWxids  == null || cachedServiceWxids.isEmpty());
                if (noFriends && noOfficial) {
                    new Handler(Looper.getMainLooper()).post(new Runnable() {
                        public void run() {
                            isDialogShowing = false;
                            log("无法获取任何联系人数据");
                            uiToast("无法获取联系人列表，请检查API权限");
                        }
                    });
                    return;
                }

                final java.util.Set<String> alreadyAdded = new java.util.HashSet<>();
                for (int i = 0; i < currentIdList.length(); i++) {
                    alreadyAdded.add(currentIdList.getString(i));
                }

                final java.util.List<String> availableFriends = new java.util.ArrayList<>();
                int excluded = 0;
                if (cachedFriendWxidList != null) {
                    for (String w : cachedFriendWxidList) {
                        if (alreadyAdded.contains(w)) excluded++;
                        else availableFriends.add(w);
                    }
                }
                final int finalExcluded = excluded;

                loadWcdbLabels(availableFriends);

                resetDialogState();
                ds_alreadyAdded = alreadyAdded;
                ds_officialFull = (cachedOfficialWxids != null)
                    ? new java.util.HashSet<>(cachedOfficialWxids) : new java.util.HashSet<>();
                ds_serviceFull  = (cachedServiceWxids != null)
                    ? new java.util.HashSet<>(cachedServiceWxids) : new java.util.HashSet<>();
                ds_enterpriseFull = (cachedEnterpriseWxids != null)
                    ? new java.util.HashSet<>(cachedEnterpriseWxids) : new java.util.HashSet<>();
                if (cachedOfficialWxids != null)
                    for (String w : cachedOfficialWxids)
                        if (!alreadyAdded.contains(w)) ds_officialWxids.add(w);
                if (cachedServiceWxids != null)
                    for (String w : cachedServiceWxids)
                        if (!alreadyAdded.contains(w)) ds_serviceWxids.add(w);
                if (cachedEnterpriseWxids != null)
                    for (String w : cachedEnterpriseWxids)
                        if (!alreadyAdded.contains(w)) ds_enterpriseWxids.add(w);
                ds_labels = cachedLabels != null
                    ? new java.util.ArrayList<>(cachedLabels) : new java.util.ArrayList<>();
                ds_labelMembers = new java.util.HashMap<>();
                if (cachedLabelMembers != null) {
                    for (java.util.Map.Entry<String, java.util.List<String>> e2 : cachedLabelMembers.entrySet()) {
                        ds_labelMembers.put(e2.getKey(), new java.util.ArrayList<>(e2.getValue()));
                    }
                }

                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    public void run() {
                        createBatchAddDialog(act, groupIndex, groupItems, groupFile, currentWxid,
                            currentIdList, availableFriends, finalExcluded);
                    }
                });

            } catch (Exception e) {
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    public void run() {
                        isDialogShowing = false;
                        log("加载联系人数据失败: " + e.getMessage());
                        uiToast("加载联系人数据失败");
                    }
                });
            }
        }
    }).start();
}

/**
 * 创建批量添加对话框UI
 * Tab 布局：全部 | 好友 | 群聊 | 订阅号 | 服务号 | [标签1] [标签2] ...
 */
private void createBatchAddDialog(Activity act, int groupIndex, JSONArray groupItems, File groupFile,
                                String currentWxid, JSONArray currentIdList,
                                final java.util.List availableFriends,
                                final int excludedCount) {
    try {
        final int TAB_FIXED = 6;
        final int totalTabs = TAB_FIXED + ds_labels.size();

        final java.util.List originalFriends = new java.util.ArrayList(availableFriends);
        final java.util.List currentDisplay = new java.util.ArrayList();
        final java.util.Set selectedWxids = new java.util.HashSet();

        Object[] _bSpec = buildStandardDialog(act, "批量添加联系人", 18,
            dp(24), dp(24), dp(24), dp(16));
        final Dialog dialog = (Dialog) _bSpec[0];
        LinearLayout root = (LinearLayout) _bSpec[1];
        int width  = ((Integer) _bSpec[2]).intValue();
        int height = ((Integer) _bSpec[3]).intValue();

        final TextView infoView = new TextView(act);
        infoView.setId(ID_INFO_TEXT);
        styleTextSecondary(infoView);
        infoView.setPadding(0, 0, 0, dp(10));
        root.addView(infoView);

        final int[] currentTab = new int[]{0};
        HorizontalScrollView tabScroll = new HorizontalScrollView(act);
        tabScroll.setHorizontalScrollBarEnabled(false);
        tabScroll.setPadding(0, 0, 0, dp(10));

        final LinearLayout tabLayout = new LinearLayout(act);
        tabLayout.setOrientation(LinearLayout.HORIZONTAL);
        tabScroll.addView(tabLayout);

        final Object[] tabButtonsHolder = new Object[1];
        tabButtonsHolder[0] = new Button[totalTabs];
        final int[] totalTabsRef = new int[]{totalTabs};

        final String[] fixedNames = {"全部", "好友", "群聊", "企业好友", "订阅号", "服务号"};
        Button[] _initBtns = (Button[]) tabButtonsHolder[0];
        for (int _t = 0; _t < totalTabs; _t++) {
            String label;
            if (_t < TAB_FIXED) {
                label = fixedNames[_t];
            } else {
                String[] lInfo = (String[]) ds_labels.get(_t - TAB_FIXED);
                label = lInfo[1];
            }
            Button btn = new Button(act);
            btn.setText(label);
            btn.setAllCaps(false);
            btn.setTextSize(13);
            btn.setPadding(dp(14), dp(5), dp(14), dp(5));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            if (_t > 0) lp.leftMargin = dp(8);
            btn.setTag(Integer.valueOf(_t));
            tabLayout.addView(btn, lp);
            _initBtns[_t] = btn;
        }
        root.addView(tabScroll);

        final Runnable[] updateTabStyle = new Runnable[1];
        updateTabStyle[0] = new Runnable() {
            public void run() {
                Button[] _btns = (Button[]) tabButtonsHolder[0];
                for (int _t = 0; _t < totalTabsRef[0]; _t++) {
                    boolean active = (currentTab[0] == _t);
                    _btns[_t].setBackground(shapeStrokeInt(active ? CI_ACCENT : CI_BUTTON_BG, dp(6), CI_CARD_STROKE));
                    _btns[_t].setTextColor(active ? Color.WHITE : CI_BUTTON_TEXT);
                }
            }
        };
        updateTabStyle[0].run();

        LinearLayout searchLayout = new LinearLayout(act);
        searchLayout.setOrientation(LinearLayout.HORIZONTAL);
        searchLayout.setPadding(0, 0, 0, dp(10));

        final EditText searchBox = new EditText(act);
        searchBox.setId(ID_SEARCH_BOX);
        searchBox.setHint("搜索联系人（昵称/wxid）");
        searchBox.setTextSize(14);
        searchBox.setPadding(dp(12), dp(8), dp(12), dp(8));
        searchBox.setBackground(shapeStrokeInt(CI_CARD_BG, dp(4), CI_CARD_STROKE));
        searchLayout.addView(searchBox, new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));

        Button searchBtn = btnSmall(act, "搜索");
        searchBtn.setId(ID_SEARCH_BUTTON);
        LinearLayout.LayoutParams sbp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sbp.leftMargin = dp(8);
        searchLayout.addView(searchBtn, sbp);

        Button clearBtn = btnSmall(act, "清空");
        LinearLayout.LayoutParams cbp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cbp.leftMargin = dp(8);
        searchLayout.addView(clearBtn, cbp);
        root.addView(searchLayout);

        LinearLayout operationLayout = new LinearLayout(act);
        operationLayout.setOrientation(LinearLayout.HORIZONTAL);
        operationLayout.setGravity(Gravity.CENTER_VERTICAL);
        operationLayout.setPadding(0, 0, 0, dp(10));

        Button selectAllBtn    = btnSmall(act, "全选");
        Button invertSelectBtn = btnSmall(act, "反选");
        LinearLayout selectLayout = new LinearLayout(act);
        selectLayout.setOrientation(LinearLayout.HORIZONTAL);
        selectLayout.addView(selectAllBtn);
        LinearLayout.LayoutParams invp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        invp.leftMargin = dp(8);
        selectLayout.addView(invertSelectBtn, invp);
        operationLayout.addView(selectLayout);

        View spacer = new View(act);
        operationLayout.addView(spacer, new LinearLayout.LayoutParams(0, dp(1), 1.0f));

        Button confirmBtn = btn(act, "确定添加");
        confirmBtn.setTextSize(11); confirmBtn.setPadding(dp(8), dp(4), dp(8), dp(4));
        Button saveBtn = btn(act, "保存");
        saveBtn.setTextSize(11);    saveBtn.setPadding(dp(8), dp(4), dp(8), dp(4));
        Button backBtn = btn(act, "返回");
        backBtn.setTextSize(11);    backBtn.setPadding(dp(8), dp(4), dp(8), dp(4));

        LinearLayout actionLayout = new LinearLayout(act);
        actionLayout.setOrientation(LinearLayout.HORIZONTAL);
        actionLayout.addView(confirmBtn);
        LinearLayout.LayoutParams sap = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sap.leftMargin = dp(6);
        actionLayout.addView(saveBtn, sap);
        LinearLayout.LayoutParams bap = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bap.leftMargin = dp(6);
        actionLayout.addView(backBtn, bap);
        operationLayout.addView(actionLayout);
        root.addView(operationLayout);

        final int _dp4 = dp(4), _dp6 = dp(6), _dp8 = dp(8), _dp12 = dp(12), _dp40 = dp(40);
        final LinearLayout.LayoutParams _rowLP = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        final LinearLayout.LayoutParams _cbLP = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        final LinearLayout.LayoutParams _infoLP = new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);

        // currentData[0]: 当前Tab的key列表（全部Tab含__header__，其他Tab仅wxid）
        // currentHeaders[0]: header key集合，用于在adapter里区分行类型
        final java.util.List[] currentData = new java.util.List[1];
        currentData[0] = new java.util.ArrayList();
        final java.util.Set[] currentHeaders = new java.util.Set[1];
        currentHeaders[0] = new java.util.HashSet();

        final ListView listView = new ListView(act);
        listView.setDivider(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        listView.setDividerHeight(_dp6);
        listView.setBackgroundColor(Color.TRANSPARENT);
        listView.setCacheColorHint(Color.TRANSPARENT);
        listView.setScrollbarFadingEnabled(true);
        // 底部padding让最后一行能完整滚出，setClipToPadding(false)使padding区域仍可滚动到
        listView.setPadding(dp(8), dp(6), dp(8), dp(48));
        listView.setClipToPadding(false);

        final BaseAdapter[] tabAdapter = new BaseAdapter[1];
        tabAdapter[0] = new BaseAdapter() {
            public int getCount() { return currentData[0].size(); }
            public Object getItem(int pos) { return currentData[0].get(pos); }
            public long getItemId(int pos) { return (long) pos; }
            public int getViewTypeCount() { return 2; }
            public int getItemViewType(int pos) {
                return currentHeaders[0].contains(currentData[0].get(pos)) ? 0 : 1;
            }
            public View getView(int pos, View convertView, ViewGroup parent) {
                String key = (String) currentData[0].get(pos);
                boolean isHeader = currentHeaders[0].contains(key);
                if (isHeader) {
                    TextView tv;
                    if (convertView instanceof TextView) {
                        tv = (TextView) convertView;
                    } else {
                        tv = new TextView(act);
                        tv.setTextSize(13);
                        tv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                        styleTextSecondary(tv);
                        tv.setPadding(_dp8, _dp6, _dp8, _dp4);
                    }
                    String label;
                    if ("__header_friend__".equals(key))           label = "👤 好友";
                    else if ("__header_group__".equals(key))       label = "💬 群聊";
                    else if ("__header_enterprise__".equals(key))  label = "🏢 企业好友";
                    else if ("__header_official__".equals(key))    label = "📢 订阅号";
                    else                                           label = "🏢 服务号";
                    int cnt = 0;
                    for (int j = pos + 1; j < currentData[0].size(); j++) {
                        if (currentHeaders[0].contains(currentData[0].get(j))) break;
                        cnt++;
                    }
                    tv.setText(label + " (" + cnt + ")");
                    return tv;
                } else {
                    // ── 联系人行（复用LinearLayout）──
                    LinearLayout row;
                    if (convertView instanceof LinearLayout) {
                        row = (LinearLayout) convertView;
                        bindContactRow(row, key, cachedFriendNameMap, selectedWxids);
                    } else {
                        row = buildContactRow(act, key, cachedFriendNameMap, selectedWxids,
                            _rowLP, _cbLP, _infoLP, _dp6, _dp8, _dp12, _dp40);
                    }
                    return row;
                }
            }
        };
        listView.setAdapter(tabAdapter[0]);

        int listH = (int)(height * 0.85f) - dp(230);
        root.addView(listView, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, listH));

        final Runnable[] fillTabData = new Runnable[1];
        fillTabData[0] = new Runnable() {
            public void run() {
                int tab = currentTab[0];
                String kw = searchBox.getText().toString().trim();
                String kwl = kw.toLowerCase();
                currentData[0].clear();
                currentHeaders[0].clear();
                if (tab == 0) {
                    java.util.List fL = new java.util.ArrayList();
                    java.util.List gL = new java.util.ArrayList();
                    java.util.List eL = new java.util.ArrayList();
                    java.util.List oL = new java.util.ArrayList();
                    java.util.List sL = new java.util.ArrayList();
                    for (int _i = 0; _i < originalFriends.size(); _i++) {
                        String wxid = (String) originalFriends.get(_i);
                        if (!kwl.isEmpty()) {
                            String name = (String) cachedFriendNameMap.get(wxid); if (name == null) name = "";
                            if (!(name + " " + wxid).toLowerCase().contains(kwl)) continue;
                        }
                        if (wxid.endsWith("@chatroom")) gL.add(wxid); else fL.add(wxid);
                    }
                    for (int _i = 0; _i < ds_enterpriseWxids.size(); _i++) {
                        String wxid = (String) ds_enterpriseWxids.get(_i);
                        if (!kwl.isEmpty()) {
                            String name = (String) cachedFriendNameMap.get(wxid); if (name == null) name = "";
                            if (!(name + " " + wxid).toLowerCase().contains(kwl)) continue;
                        }
                        eL.add(wxid);
                    }
                    for (int _i = 0; _i < ds_officialWxids.size(); _i++) {
                        String wxid = (String) ds_officialWxids.get(_i);
                        if (!kwl.isEmpty()) {
                            String name = (String) cachedFriendNameMap.get(wxid); if (name == null) name = "";
                            if (!(name + " " + wxid).toLowerCase().contains(kwl)) continue;
                        }
                        oL.add(wxid);
                    }
                    for (int _i = 0; _i < ds_serviceWxids.size(); _i++) {
                        String wxid = (String) ds_serviceWxids.get(_i);
                        if (!kwl.isEmpty()) {
                            String name = (String) cachedFriendNameMap.get(wxid); if (name == null) name = "";
                            if (!(name + " " + wxid).toLowerCase().contains(kwl)) continue;
                        }
                        sL.add(wxid);
                    }
                    if (!fL.isEmpty()) { currentData[0].add("__header_friend__");     currentHeaders[0].add("__header_friend__");     currentData[0].addAll(fL); }
                    if (!gL.isEmpty()) { currentData[0].add("__header_group__");      currentHeaders[0].add("__header_group__");      currentData[0].addAll(gL); }
                    if (!eL.isEmpty()) { currentData[0].add("__header_enterprise__"); currentHeaders[0].add("__header_enterprise__"); currentData[0].addAll(eL); }
                    if (!oL.isEmpty()) { currentData[0].add("__header_official__");   currentHeaders[0].add("__header_official__");   currentData[0].addAll(oL); }
                    if (!sL.isEmpty()) { currentData[0].add("__header_service__");    currentHeaders[0].add("__header_service__");    currentData[0].addAll(sL); }
                } else if (tab == 1) {
                    for (int _i = 0; _i < originalFriends.size(); _i++) {
                        String wxid = (String) originalFriends.get(_i);
                        if (wxid.endsWith("@chatroom")) continue;
                        if (!kwl.isEmpty()) {
                            String name = (String) cachedFriendNameMap.get(wxid); if (name == null) name = "";
                            if (!(name + " " + wxid).toLowerCase().contains(kwl)) continue;
                        }
                        currentData[0].add(wxid);
                    }
                } else if (tab == 2) {
                    for (int _i = 0; _i < originalFriends.size(); _i++) {
                        String wxid = (String) originalFriends.get(_i);
                        if (!wxid.endsWith("@chatroom")) continue;
                        if (!kwl.isEmpty()) {
                            String name = (String) cachedFriendNameMap.get(wxid); if (name == null) name = "";
                            if (!(name + " " + wxid).toLowerCase().contains(kwl)) continue;
                        }
                        currentData[0].add(wxid);
                    }
                } else if (tab == 3) {
                    for (int _i = 0; _i < ds_enterpriseWxids.size(); _i++) {
                        String wxid = (String) ds_enterpriseWxids.get(_i);
                        if (!kwl.isEmpty()) {
                            String name = (String) cachedFriendNameMap.get(wxid); if (name == null) name = "";
                            if (!(name + " " + wxid).toLowerCase().contains(kwl)) continue;
                        }
                        currentData[0].add(wxid);
                    }
                } else if (tab == 4) {
                    for (int _i = 0; _i < ds_officialWxids.size(); _i++) {
                        String wxid = (String) ds_officialWxids.get(_i);
                        if (!kwl.isEmpty()) {
                            String name = (String) cachedFriendNameMap.get(wxid); if (name == null) name = "";
                            if (!(name + " " + wxid).toLowerCase().contains(kwl)) continue;
                        }
                        currentData[0].add(wxid);
                    }
                } else if (tab == 5) {
                    for (int _i = 0; _i < ds_serviceWxids.size(); _i++) {
                        String wxid = (String) ds_serviceWxids.get(_i);
                        if (!kwl.isEmpty()) {
                            String name = (String) cachedFriendNameMap.get(wxid); if (name == null) name = "";
                            if (!(name + " " + wxid).toLowerCase().contains(kwl)) continue;
                        }
                        currentData[0].add(wxid);
                    }
                } else {
                    String[] lInfo = (String[]) ds_labels.get(tab - TAB_FIXED);
                    java.util.List members = (java.util.List) ds_labelMembers.get(lInfo[0]);
                    if (members != null) {
                        for (int _i = 0; _i < members.size(); _i++) {
                            String wxid = (String) members.get(_i);
                            if (!kwl.isEmpty()) {
                                String name = (String) cachedFriendNameMap.get(wxid); if (name == null) name = "";
                                if (!(name + " " + wxid).toLowerCase().contains(kwl)) continue;
                            }
                            currentData[0].add(wxid);
                        }
                    }
                }
            }
        };

        final Runnable[] renderCurrentTab = new Runnable[1];
        renderCurrentTab[0] = new Runnable() {
            public void run() {
                int tab = currentTab[0];
                fillTabData[0].run();
                currentDisplay.clear();
                for (int i = 0; i < currentData[0].size(); i++) {
                    String k = (String) currentData[0].get(i);
                    if (!currentHeaders[0].contains(k)) currentDisplay.add(k);
                }
                int tabExcluded = 0;
                for (String wxid : ds_alreadyAdded) {
                    boolean belongs;
                    if (tab == 0) { belongs = true; }
                    else if (tab == 1) { belongs = !wxid.endsWith("@chatroom") && !ds_officialFull.contains(wxid) && !ds_serviceFull.contains(wxid) && !ds_enterpriseFull.contains(wxid); }
                    else if (tab == 2) { belongs = wxid.endsWith("@chatroom"); }
                    else if (tab == 3) { belongs = ds_enterpriseFull.contains(wxid); }
                    else if (tab == 4) { belongs = ds_officialFull.contains(wxid); }
                    else if (tab == 5) { belongs = ds_serviceFull.contains(wxid); }
                    else {
                        String[] lInfo = (String[]) ds_labels.get(tab - TAB_FIXED);
                        java.util.List allM = (java.util.List) ds_labelMembers.get(lInfo[0]);
                        belongs = allM != null && allM.contains(wxid);
                    }
                    if (belongs) tabExcluded++;
                }
                updateInfoText(infoView, currentDisplay.size(), tabExcluded);
                tabAdapter[0].notifyDataSetChanged();
                listView.setSelection(0);
            }
        };

        int tab0Total = originalFriends.size() + ds_enterpriseWxids.size() + ds_officialWxids.size() + ds_serviceWxids.size();
        infoView.setText("当前 Tab 可选：" + tab0Total + " 个"
            + "　|　分组已有成员：" + ds_alreadyAdded.size() + " 个（当前tab）");
        showDialogFullish(_bSpec);
        if (!dialog.isShowing()) return;

        renderCurrentTab[0].run();

        Button[] _allBtnsRef = (Button[]) tabButtonsHolder[0];
        for (int _t = 0; _t < totalTabs; _t++) {
            _allBtnsRef[_t].setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    currentTab[0] = ((Integer) v.getTag()).intValue();
                    updateTabStyle[0].run();
                    renderCurrentTab[0].run();
                }
            });
        }

        searchBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { renderCurrentTab[0].run(); }
        });
        clearBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                searchBox.setText("");
                renderCurrentTab[0].run();
            }
        });

        selectAllBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                selectedWxids.addAll(currentDisplay);
                tabAdapter[0].notifyDataSetChanged();
            }
        });
        invertSelectBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                for (int _i = 0; _i < currentDisplay.size(); _i++) {
                    String wxid = (String) currentDisplay.get(_i);
                    if (selectedWxids.contains(wxid)) selectedWxids.remove(wxid);
                    else selectedWxids.add(wxid);
                }
                tabAdapter[0].notifyDataSetChanged();
            }
        });

        confirmBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                processFriendSelection(selectedWxids, originalFriends, currentDisplay,
                    currentData, currentHeaders, infoView, excludedCount, cachedFriendNameMap,
                    currentIdList, groupItems, groupFile, dialog, true, tabAdapter);
            }
        });
        saveBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                processFriendSelection(selectedWxids, originalFriends, currentDisplay,
                    currentData, currentHeaders, infoView, excludedCount, cachedFriendNameMap,
                    currentIdList, groupItems, groupFile, dialog, false, tabAdapter);
            }
        });
        backBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                dialog.dismiss();
                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                    public void run() {
                        try {
                            showGroupDetailDialog(groupIndex, groupItems, groupFile, currentWxid);
                        } catch (Throwable e) {
                            log("打开分组详情失败: " + e.getMessage());
                            uiToast("❌ 打开分组详情失败");
                        }
                    }
                }, 100);
            }
        });

        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            public void onDismiss(DialogInterface d) {
                isDialogShowing = false;
                selectedWxids.clear();
                currentDisplay.clear();
                originalFriends.clear();
                currentData[0].clear();
                avatarPendingViews.clear();
            }
        });
    } catch (Exception e) {
        isDialogShowing = false;
        log("创建批量添加对话框UI失败: " + e.getMessage());
        uiToast("创建批量添加对话框UI失败");
    }
}

/**
 * 处理好友选择（确定添加 / 保存）
 * selectedWxids：纯数据 Set，无 View 依赖
 * 保存后：从 originalFriends/currentDisplay 移除已添加项，刷新 ListView
 */
private void processFriendSelection(java.util.Set<String> selectedWxids,
                                  java.util.List<String> originalFriends,
                                  java.util.List<String> currentDisplay,
                                  java.util.List[] currentData,
                                  java.util.Set[] currentHeaders,
                                  TextView infoView, int excludedCount,
                                  java.util.Map<String, String> nameMap,
                                  JSONArray currentIdList, JSONArray groupItems,
                                  File groupFile, Dialog dialog, boolean closeDialog,
                                  BaseAdapter[] tabAdapter) {
    try {
        if (selectedWxids.isEmpty()) {
            uiToast("⚠️ 请选择要添加的联系人");
            return;
        }

        int selectedCount = 0;
        int duplicateCount = 0;
        java.util.List<String> toAdd = new java.util.ArrayList<>();

        java.util.Set<String> existingSet = new java.util.HashSet<>();
        for (int j = 0; j < currentIdList.length(); j++) {
            existingSet.add(currentIdList.getString(j));
        }
        for (String wxid : selectedWxids) {
            if (existingSet.contains(wxid)) { duplicateCount++; }
            else { currentIdList.put(wxid); toAdd.add(wxid); selectedCount++; }
        }

        if (selectedCount == 0 && duplicateCount == 0) {
            uiToast("⚠️ 请选择要添加的联系人");
            return;
        }

        saveGroupData(groupItems, groupFile);

        String message = "✅ 成功" + (closeDialog ? "添加" : "保存") + " " + selectedCount + " 个联系人";
        if (duplicateCount > 0) message += "，跳过 " + duplicateCount + " 个重复";
        if (!closeDialog) message += "，可继续选择";
        uiToast(message);

        if (closeDialog) {
            dialog.dismiss();
        } else {
            originalFriends.removeAll(toAdd);
            currentDisplay.removeAll(toAdd);
            ds_officialWxids.removeAll(toAdd);
            ds_serviceWxids.removeAll(toAdd);
            ds_enterpriseWxids.removeAll(toAdd);
            for (java.util.List<String> members : ds_labelMembers.values()) {
                members.removeAll(toAdd);
            }
            currentData[0].removeAll(toAdd);

            selectedWxids.clear();
            tabAdapter[0].notifyDataSetChanged();

            if (infoView != null) {
                infoView.setText("当前 Tab 可选：" + currentDisplay.size() + " 个"
                    + "　|　分组已有成员：" + excludedCount + " 个（当前tab）");
            }
        }

    } catch (Exception e) {
        log("处理好友选择失败: " + e.getMessage());
        uiToast("❌ 操作失败");
    }
}

/**
 * 直接更新 infoView 文字（供 renderCurrentTab 使用，不需要通过 Dialog.findViewById）
 * availableCount：当前 Tab 过滤后的可选数量
 * excludedCount：当前 Tab 中已在分组里的成员数
 */
private void updateInfoText(TextView infoView, int availableCount, int excludedCount) {
    try {
        if (infoView != null) {
            infoView.setText("当前 Tab 可选：" + availableCount + " 个"
                + "　|　分组已有成员：" + excludedCount + " 个（当前tab）");
        }
    } catch (Exception e) {
        log("更新info文字失败: " + e.getMessage());
    }
}

/**
 * 更新批量添加对话框中的好友数量提示信息
 */
private void updateBatchAddInfoText(Dialog dialog, int availableCount, int excludedCount) {
    try {
        TextView infoText = dialog.findViewById(ID_INFO_TEXT);
        if (infoText != null) {
            infoText.setText("当前 Tab 可选：" + availableCount + " 个"
                + "　|　分组已有成员：" + excludedCount + " 个（当前tab）");
        }
    } catch (Exception e) {
        log("更新批量添加提示信息失败: " + e.getMessage());
    }
}

/**
 * 处理分组选择（选择按钮直接将当前wxid加入分组）
 */
void handleGroupSelection(String groupTitle, JSONArray idList, String currentWxid, JSONArray groupItems, File groupFile) {
    try {
        boolean exists = false;
        for (int i = 0; i < idList.length(); i++) {
            if (idList.getString(i).equals(currentWxid)) {
                exists = true;
                break;
            }
        }

        if (exists) {
            uiToast("⚠️ wxid已存在于分组");
        } else {
            idList.put(currentWxid);
            // fix⑤: 使用saveGroupData统一保存
            saveGroupData(groupItems, groupFile);
            uiToast("✅ 已添加wxid到分组");
        }

    } catch (Exception e) {
        log("处理分组选择失败: " + e.getMessage());
        uiToast("❌ 处理分组选择失败");
    }
}

/**
 * 显示创建新分组对话框
 */
void showCreateNewGroupDialog(JSONArray groupItems, String currentWxid, File groupFile, Dialog parentDialog) {
    Activity act = getTopActivity();
    if (act == null) {
        uiToast("无法获取当前Activity");
        return;
    }

    new Handler(Looper.getMainLooper()).post(new Runnable() {
        public void run() {
            try {
                Object[] _spec = buildStandardDialog(act, "添加新分组");
                final Dialog addDialog = (Dialog) _spec[0];
                LinearLayout root = (LinearLayout) _spec[1];

                TextView info = new TextView(act);
                info.setText("请输入新分组的名称");
                styleTextSecondary(info);
                info.setPadding(0, 0, 0, dp(16));
                root.addView(info);

                EditText groupNameInput = new EditText(act);
                groupNameInput.setHint("分组名称");
                groupNameInput.setPadding(dp(16), dp(12), dp(16), dp(12));
                groupNameInput.setBackground(shapeStrokeInt(CI_CARD_BG, dp(8), CI_CARD_STROKE));
                try {
                    groupNameInput.setTextColor(CI_TEXT_PRIMARY);
                    groupNameInput.setHintTextColor(CI_HINT_TEXT);
                } catch (Throwable ignore) {}
                root.addView(groupNameInput);

                LinearLayout buttonLayout = new LinearLayout(act);
                buttonLayout.setOrientation(LinearLayout.HORIZONTAL);
                buttonLayout.setGravity(Gravity.CENTER);

                Button confirmBtn = btn(act, "确定");
                LinearLayout.LayoutParams lpConfirm = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
                lpConfirm.rightMargin = dp(8);
                buttonLayout.addView(confirmBtn, lpConfirm);

                Button cancelBtn = btn(act, "取消");
                LinearLayout.LayoutParams lpCancel = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
                lpCancel.leftMargin = dp(8);
                buttonLayout.addView(cancelBtn, lpCancel);

                root.addView(buttonLayout);



                confirmBtn.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        String groupName = groupNameInput.getText().toString().trim();
                        if (groupName.isEmpty()) {
                            uiToast("⚠️ 请输入分组名称");
                            return;
                        }
                        try {
                            for (int i = 0; i < groupItems.length(); i++) {
                                if (groupItems.getJSONObject(i).getString("title").equals(groupName)) {
                                    uiToast("⚠️ 分组名称已存在");
                                    return;
                                }
                            }
                            JSONObject newGroup = new JSONObject();
                            newGroup.put("type", "custom");
                            newGroup.put("title", groupName);
                            newGroup.put("order", groupItems.length());
                            newGroup.put("enable", true);
                            newGroup.put("idList", new JSONArray());
                            groupItems.put(newGroup);

                            // fix⑤: 使用saveGroupData统一保存
                            saveGroupData(groupItems, groupFile);
                            addDialog.dismiss();
                            uiToast("✅ 已添加新分组");
                            parentDialog.dismiss();
                            isDialogShowing = false;
                            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                                public void run() {
                                    showGroupSelectionDialog(groupItems, currentWxid, groupFile);
                                }
                            }, 100);
                        } catch (Exception e) {
                            log("添加分组失败: " + e.getMessage());
                            uiToast("❌ 添加分组失败");
                        }
                    }
                });

                cancelBtn.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        addDialog.dismiss();
                    }
                });

                showDialogWrap(_spec);

            } catch (Throwable e) {
                log("创建添加分组对话框失败: " + e.getMessage());
                uiToast("创建添加分组对话框失败");
            }
        }
    });
}

/**
 * 显示分组详情页面
 */
void showGroupDetailDialog(int groupIndex, JSONArray groupItems, File groupFile, String currentWxid) {
    applyTheme();
    Activity act = getTopActivity();
    if (act == null) {
        uiToast("无法获取当前Activity");
        return;
    }
    isDialogShowing = true;

    new Handler(Looper.getMainLooper()).post(new Runnable() {
        public void run() {
            try {
                Object[] _spec = buildStandardDialog(act, "分组详情");
                final Dialog detailDialog = (Dialog) _spec[0];
                LinearLayout root = (LinearLayout) _spec[1];

                JSONObject group = groupItems.getJSONObject(groupIndex);
                String title = group.getString("title");
                JSONArray idList = group.getJSONArray("idList");
                boolean enable = group.optBoolean("enable", true);

                // 分组名称编辑
                LinearLayout nameLayout = new LinearLayout(act);
                nameLayout.setOrientation(LinearLayout.HORIZONTAL);
                nameLayout.setGravity(Gravity.CENTER_VERTICAL);
                nameLayout.setPadding(0, 0, 0, dp(16));

                TextView nameLabel = new TextView(act);
                nameLabel.setText("分组名称: ");
                styleTextPrimary(nameLabel);
                nameLayout.addView(nameLabel);

                EditText nameInput = new EditText(act);
                nameInput.setText(title);
                nameInput.setPadding(dp(12), dp(8), dp(12), dp(8));
                nameInput.setBackground(shapeStrokeInt(CI_CARD_BG, dp(6), CI_CARD_STROKE));
                try { nameInput.setTextColor(CI_TEXT_PRIMARY); } catch (Throwable ignore) {}
                nameInput.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));
                nameLayout.addView(nameInput);
                root.addView(nameLayout);

                // 启用状态切换
                LinearLayout enableLayout = new LinearLayout(act);
                enableLayout.setOrientation(LinearLayout.HORIZONTAL);
                enableLayout.setGravity(Gravity.CENTER_VERTICAL);
                enableLayout.setPadding(0, 0, 0, dp(16));

                TextView enableLabel = new TextView(act);
                enableLabel.setText("启用状态: ");
                styleTextPrimary(enableLabel);
                enableLayout.addView(enableLabel);

                Switch enableSwitch = new Switch(act);
                enableSwitch.setChecked(enable);
                enableLayout.addView(enableSwitch);
                root.addView(enableLayout);

                // 成员列表标题
                TextView memberTitle = new TextView(act);
                memberTitle.setId(ID_MEMBER_TITLE);
                memberTitle.setText("成员列表 (" + idList.length() + "个)");
                memberTitle.setTextSize(16);
                styleTextPrimary(memberTitle);
                memberTitle.setPadding(0, 0, 0, dp(12));
                root.addView(memberTitle);

                // 成员列表滚动区域
                ScrollView memberScroll = new ScrollView(act);
                memberScroll.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(200)));
                LinearLayout memberContainer = new LinearLayout(act);
                memberContainer.setOrientation(LinearLayout.VERTICAL);
                memberContainer.setId(ID_MEMBER_CONTAINER); // fix⑩: 统一使用常量
                memberScroll.addView(memberContainer);
                root.addView(memberScroll);

                // 成员列表渲染和刷新方法
                Runnable refreshMemberListUI = new Runnable() {
                    public void run() {
                        try {
                        memberContainer.removeAllViews();
                        JSONArray idListNow = group.getJSONArray("idList");
                        for (int i = 0; i < idListNow.length(); i++) {
                            String memberWxid = idListNow.getString(i); // fix③: 改名避免遮蔽外层currentWxid
                            String friendName = "";
                            try {
                                friendName = getFriendName(memberWxid);
                            } catch (Throwable e) {
                            }

                            LinearLayout memberItem = new LinearLayout(act);
                            memberItem.setOrientation(LinearLayout.HORIZONTAL);
                            memberItem.setPadding(dp(12), dp(8), dp(12), dp(8));
                            memberItem.setBackground(shapeStrokeInt(CI_CARD_BG, dp(6), CI_CARD_STROKE));
                            memberItem.setLayoutParams(new LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

                            // fix⑦: 统一使用loadAvatar，支持会话缓存
                            ImageView avatarView = new ImageView(act);
                            int avatarSize = dp(40);
                            LinearLayout.LayoutParams avatarParams = new LinearLayout.LayoutParams(avatarSize, avatarSize);
                            avatarParams.setMargins(0, 0, dp(12), 0);
                            avatarView.setLayoutParams(avatarParams);
                            avatarView.setScaleType(ImageView.ScaleType.CENTER_CROP);
                            GradientDrawable ph = new GradientDrawable();
                            ph.setColor(Color.GRAY); // 同上
                            ph.setCornerRadius(dp(6));
                            avatarView.setBackground(ph);
                            loadAvatar(avatarView, memberWxid);

                            memberItem.addView(avatarView);

                            LinearLayout memberInfoLayout = new LinearLayout(act);
                            memberInfoLayout.setOrientation(LinearLayout.VERTICAL);
                            memberInfoLayout.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));

                            if (!friendName.isEmpty() && !friendName.equals(memberWxid)) {
                                TextView nameText = new TextView(act);
                                nameText.setText(friendName);
                                nameText.setTextSize(14);
                                styleTextPrimary(nameText);
                                memberInfoLayout.addView(nameText);
                            }

                            TextView wxidText = new TextView(act);
                            wxidText.setText(memberWxid);
                            wxidText.setTextSize(12);
                            styleTextSecondary(wxidText);
                            memberInfoLayout.addView(wxidText);
                            memberItem.addView(memberInfoLayout);

                            Button removeBtn = btnSmall(act, "移出");
                            removeBtn.setTag(memberWxid);
                            removeBtn.setOnClickListener(new View.OnClickListener() {
                                public void onClick(View v) {
                                    try {
                                        String targetWxid = (String) v.getTag();
                                        JSONArray newIdList = new JSONArray();
                                        JSONArray curIdList = group.getJSONArray("idList");
                                        for (int j = 0; j < curIdList.length(); j++) {
                                            String mid = curIdList.getString(j); // fix③: 不遮蔽外层currentWxid
                                            if (!mid.equals(targetWxid)) {
                                                newIdList.put(mid);
                                            }
                                        }
                                        group.put("idList", newIdList);
                                        // fix⑤: 使用saveGroupData统一保存
                                        saveGroupData(groupItems, groupFile);
                                        uiToast("✅ 已移出成员");
                                        refreshMemberListUI.run();
                                        memberTitle.setText("成员列表 (" + newIdList.length() + "个)");
                                    } catch (Exception e) {
                                        log("移出成员失败: " + e.getMessage());
                                        uiToast("❌ 移出失败");
                                    }
                                }
                            });

                            memberItem.addView(removeBtn);
                            memberContainer.addView(memberItem);
                        }
                        } catch (Throwable e) {
                            log("refreshMemberListUI 异常: " + e.getClass().getName() + ": " + e.getMessage());
                            uiToast("❌ 成员列表加载失败");
                        }
                    }
                };

                refreshMemberListUI.run();

                // 底部按钮区域
                LinearLayout buttonLayout = new LinearLayout(act);
                buttonLayout.setOrientation(LinearLayout.HORIZONTAL);
                buttonLayout.setGravity(Gravity.CENTER);
                buttonLayout.setPadding(0, dp(20), 0, 0);

                Button saveBtn = btn(act, "保存");
                LinearLayout.LayoutParams lpSave = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
                lpSave.rightMargin = dp(6);
                buttonLayout.addView(saveBtn, lpSave);

                Button batchAddBtn = btnSmall(act, "批量添加");
                batchAddBtn.setTextSize(12);
                batchAddBtn.setPadding(dp(8), dp(6), dp(8), dp(6));
                batchAddBtn.setBackground(shapeStroke("#4CAF50", dp(4), "#66BB6A"));
                batchAddBtn.setTextColor(Color.WHITE);
                LinearLayout.LayoutParams lpBatchAdd = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
                lpBatchAdd.leftMargin = dp(6);
                lpBatchAdd.rightMargin = dp(6);
                buttonLayout.addView(batchAddBtn, lpBatchAdd);

                Button deleteBtn = btnSmall(act, "删除分组");
                deleteBtn.setTextSize(12);
                deleteBtn.setPadding(dp(8), dp(6), dp(8), dp(6));
                deleteBtn.setBackground(shapeStroke("#FF4444", dp(4), "#FF6666"));
                deleteBtn.setTextColor(Color.WHITE);
                LinearLayout.LayoutParams lpDelete = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
                lpDelete.leftMargin = dp(6);
                lpDelete.rightMargin = dp(6);
                buttonLayout.addView(deleteBtn, lpDelete);

                Button backBtn = btn(act, "返回");
                LinearLayout.LayoutParams lpBack = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
                lpBack.leftMargin = dp(6);
                buttonLayout.addView(backBtn, lpBack);

                root.addView(buttonLayout);


                // 保存按钮
                saveBtn.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        try {
                            String newTitle = nameInput.getText().toString().trim();
                            if (newTitle.isEmpty()) {
                                uiToast("⚠️ 分组名称不能为空");
                                return;
                            }
                            for (int i = 0; i < groupItems.length(); i++) {
                                if (i != groupIndex) {
                                    if (groupItems.getJSONObject(i).getString("title").equals(newTitle)) {
                                        uiToast("⚠️ 分组名称已存在");
                                        return;
                                    }
                                }
                            }
                            group.put("title", newTitle);
                            group.put("enable", enableSwitch.isChecked());
                            // fix⑤: 使用saveGroupData统一保存
                            saveGroupData(groupItems, groupFile);
                            uiToast("✅ 分组信息已保存");
                            detailDialog.dismiss();
                            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                                public void run() {
                                    showGroupSelectionDialog(groupItems, currentWxid, groupFile);
                                }
                            }, 100);
                        } catch (Exception e) {
                            log("保存分组信息失败: " + e.getMessage());
                            uiToast("❌ 保存失败");
                        }
                    }
                });

                // 删除按钮
                deleteBtn.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        try {
                            AlertDialog.Builder builder = new AlertDialog.Builder(act);
                            builder.setTitle("确认删除");
                            builder.setMessage("确定要删除分组 \"" + title + "\" 吗？");
                            builder.setPositiveButton("确定", new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface dialog, int which) {
                                    try {
                                        JSONArray newGroupItems = new JSONArray();
                                        for (int i = 0; i < groupItems.length(); i++) {
                                            if (i != groupIndex) {
                                                newGroupItems.put(groupItems.getJSONObject(i));
                                            }
                                        }
                                        // fix⑤: 使用saveGroupData统一保存
                                        saveGroupData(newGroupItems, groupFile);
                                        uiToast("✅ 分组已删除");
                                        detailDialog.dismiss();
                                        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                                            public void run() {
                                                showGroupSelectionDialog(newGroupItems, currentWxid, groupFile);
                                            }
                                        }, 100);
                                    } catch (Exception e) {
                                        log("删除分组失败: " + e.getMessage());
                                        uiToast("❌ 删除失败");
                                    }
                                }
                            });
                            builder.setNegativeButton("取消", null);
                            builder.show();
                        } catch (Exception e) {
                            log("删除操作失败: " + e.getMessage());
                            uiToast("❌ 删除操作失败");
                        }
                    }
                });

                // 返回按钮
                backBtn.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        detailDialog.dismiss();
                        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                            public void run() {
                                showGroupSelectionDialog(groupItems, currentWxid, groupFile);
                            }
                        }, 100);
                    }
                });

                // 批量添加按钮
                batchAddBtn.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        detailDialog.dismiss();
                        showBatchAddFriendsDialog(groupIndex, groupItems, groupFile, currentWxid);
                    }
                });

                detailDialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
                    public void onDismiss(DialogInterface d) {
                        isDialogShowing = false;
                    }
                });

                showDialogWrap(_spec);

            } catch (Throwable e) {
                isDialogShowing = false;
                String exClass = e.getClass().getName();
                String exMsg   = e.getMessage();
                log("showGroupDetailDialog 异常: " + exClass + ": " + exMsg);
                uiToast("创建详情页面失败");
            }
        }
    });
}