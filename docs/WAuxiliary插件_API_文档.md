# WAuxiliary插件 API 文档

【AI 强制规则】

1. 必须**完全使用本文档内的变量、方法、参数、注释**

2. 禁止新增、删除、修改任何字段名、方法名、类型

3. 禁止脑补不存在的 API

4. 所有中文注释必须严格沿用文档内描述

---

## 变量说明

```Plain Text

hostContext       宿主上下文
hostVerName       宿主版本名
hostVerCode       宿主版本号
hostVerClient     宿主客户端
moduleVer         模块版本
cacheDir          缓存目录
pluginDir         插件目录
pluginId          插件标识
pluginName        插件名称
pluginAuthor      插件作者
pluginVersion     插件版本
pluginUpdateTime  插件更新时间
```

## 回调方法

```Java

// 插件加载
void onLoad();

// 插件卸载
void onUnLoad();

// 监听收到消息
void onHandleMsg(Object msgInfoBean);

// 单击发送按钮
boolean onClickSendBtn(String text);

// 监听成员变动
void onMemberChange(String type, String groupWxid, String userWxid, String userName);

// 监听好友申请
void onNewFriend(String wxid, String ticket, int scene);
```

## 消息结构 MsgInfo

```Java

long getMsgId();            // 消息Id
int getType();              // 消息类型
long getCreateTime();       // 创建时间
String getTalker();         // 聊天Id(群聊/私聊)
String getSendTalker();     // 发送者Id
String getContent();        // 消息内容

String getMsgSource();      // 消息来源
List<String> getAtUserList(); // 艾特列表
boolean isAnnounceAll();    // 公告通知全体
boolean isNotifyAll();      // 艾特通知全体
boolean isAtMe();           // 艾特我

QuoteMsg getQuoteMsg();     // 引用消息
PatMsg getPatMsg();         // 拍一拍消息
FileMsg getFileMsg();       // 文件消息

boolean isPrivateChat();    // 私聊
boolean isOpenIM();         // 企业微信
boolean isGroupChat();      // 群聊
boolean isChatroom();       // 普通群聊
boolean isImChatroom();     // 企业群聊
boolean isOfficialAccount();// 公众号

boolean isSend();           // 自己发的

boolean isText();           // 文本
boolean isImage();          // 图片
boolean isVoice();          // 语音
boolean isShareCard();      // 名片
boolean isVideo();          // 视频
boolean isEmoji();          // 表情
boolean isLocation();       // 位置
boolean isApp();            // 应用
boolean isVoip();           // 通话
boolean isVoipVoice();      // 语音通话
boolean isVoipVideo();      // 视频通话
boolean isSystem();         // 系统
boolean isLink();           // 链接
boolean isTransfer();       // 转账
boolean isRedBag();         // 红包
boolean isVideoNumberVideo();// 视频号视频
boolean isNote();           // 接龙
boolean isQuote();          // 引用
boolean isPat();            // 拍一拍
boolean isFile();           // 文件
```

### QuoteMsg

```Java

String getTitle();          // 回复标题
String getMsgSource();      // 消息来源
String getSendTalker();     // 发送者Id
String getDisplayName();    // 显示昵称
String getTalker();         // 聊天Id(群聊/私聊)
int getType();              // 消息类型
String getContent();        // 消息内容
```

### PatMsg

```Java

String getTalker();         // 聊天Id(群聊/私聊)
String getFromUser();       // 发起者Id
String getPattedUser();     // 被拍者Id
String getTemplate();       // 模板内容
long getCreateTime();       // 创建时间
```

### FileMsg

```Java

String getTitle();          // 文件标题
long getSize();             // 文件字节
String getExt();            // 文件后缀
String getMd5();            // 文件MD5
```

## 音频方法

```Java

// mp3 到 Silk 文件
File mp3ToSilkFile(String mp3Path);

// mp3 到 Silk 路径
String mp3ToSilkPath(String mp3Path);

// mp3 到 Silk
void mp3ToSilk(String mp3Path, String silkPath);

// silk 到 Mp3 文件
File silkToMp3File(String silkPath);

// silk 到 Mp3 路径
String silkToMp3Path(String silkPath);

// silk 到 Mp3
void silkToMp3(String silkPath, String mp3Path);

// 取 silk 毫秒时长
int getSilkDuration(String silkPath);
```

## 配置方法 - 读取

```Java

String getString(String key, String defValue);
Set getStringSet(String key, Set defValue);
boolean getBoolean(String key, boolean defValue);
int getInt(String key, int defValue);
float getFloat(String key, float defValue);
long getLong(String key, long defValue);
```

## 配置方法 - 写入

```Java

void putString(String key, String value);
void putStringSet(String key, Set value);
void putBoolean(String key, boolean value);
void putInt(String key, int value);
void putFloat(String key, float value);
void putLong(String key, long value);
```

## 联系方法

```Java

// 取当前登录Wxid
String getLoginWxid();

// 取当前登录微信号
String getLoginAlias();

// 取上下文Wxid
String getTargetTalker();

// 取好友列表
List<FriendInfo> getFriendList();

// 取好友昵称
String getFriendName(String friendWxid);
String getFriendName(String friendWxid, String roomId);

// 取头像链接
void getAvatarUrl(String username);
void getAvatarUrl(String username, boolean isBigHeadImg);

// 取群聊列表
List<GroupInfo> getGroupList();

// 取群成员列表
List<String> getGroupMemberList(String groupWxid);

// 取群成员数量
int getGroupMemberCount(String groupWxid);

// 添加群成员
void addChatroomMember(String chatroomId, String addMember);
void addChatroomMember(String chatroomId, List<String> addMemberList);

// 邀请群成员
void inviteChatroomMember(String chatroomId, String inviteMember);
void inviteChatroomMember(String chatroomId, List<String> inviteMemberList);

// 移除群成员
void delChatroomMember(String chatroomId, String delMember);
void delChatroomMember(String chatroomId, List<String> delMemberList);

// 通过好友申请
void verifyUser(String wxid, String ticket, int scene);
void verifyUser(String wxid, String ticket, int scene, int privacy);

// 修改好友标签
void modifyContactLabelList(String username, String labelName);
void modifyContactLabelList(String username, List<String> labelNames);
```

## 网络方法

```Java

// get
void get(String url, Map<String, String> headerMap, PluginCallBack.HttpCallback callback);
void get(String url, Map<String, String> headerMap, long timeout, PluginCallBack.HttpCallback callback);

// post
void post(String url, Map<String, String> paramMap, Map<String, String> headerMap, PluginCallBack.HttpCallback callback);
void post(String url, Map<String, String> paramMap, Map<String, String> headerMap, long timeout, PluginCallBack.HttpCallback callback);

// download
void download(String url, String path, Map<String, String> headerMap, PluginCallBack.DownloadCallback callback);
void download(String url, String path, Map<String, String> headerMap, long timeout, PluginCallBack.DownloadCallback callback);
```

## 媒体方法

```Java

// 发送媒体
void sendMediaMsg(String talker, MediaMessage mediaMessage, String appId);

// 分享文件
void shareFile(String talker, String title, String filePath, String appId);

// 分享小程序
void shareMiniProgram(String talker, String title, String description, String userName, String path, byte[] thumbData, String appId);

// 分享音乐
void shareMusic(String talker, String title, String description, String musicUrl, String musicDataUrl, byte[] thumbData, String appId);

// 分享音乐视频
void shareMusicVideo(String talker, String title, String description, String musicUrl, String musicDataUrl, String singerName, String duration, String songLyric, byte[] thumbData, String appId);

// 分享文本
void shareText(String talker, String text, String appId);

// 分享视频
void shareVideo(String talker, String title, String description, String videoUrl, byte[] thumbData, String appId);

// 分享网页
void shareWebpage(String talker, String title, String description, String webpageUrl, byte[] thumbData, String appId);
```

## 消息方法

```Java

// 发送文本消息
void sendText(String talker, String content);

// 发送语音消息
void sendVoice(String talker, String sendPath);
void sendVoice(String talker, String sendPath, int duration);

// 发送图片消息
void sendImage(String talker, String sendPath);
void sendImage(String talker, String sendPath, String appId);

// 发送视频消息
void sendVideo(String talker, String sendPath);

// 发送表情消息
void sendEmoji(String talker, String sendPath);

// 发送拍一拍
void sendPat(String talker, String pattedUser);

// 发送分享名片
void sendShareCard(String talker, String wxid);

// 发送位置消息
void sendLocation(String talker, String poiName, String label, String x, String y, String scale);
void sendLocation(String talker, JSONObject jsonObj);

// 发送密文消息
void sendCipherMsg(String talker, String title, String content);

// 发送小程序消息
void sendAppBrandMsg(String talker, String title, String pagePath, String ghName);

// 发送接龙消息
void sendNoteMsg(String talker, String content);

// 发送引用消息
void sendQuoteMsg(String talker, long msgId, String content);

// 撤回指定消息
void revokeMsg(long msgId);

// 插入系统消息
void insertSystemMsg(String talker, String content, long createTime);
```

## 其他方法

```Java

// 执行
void eval(String code);

// 导入Java
void loadJava(String path);

// 导入Dex
void loadDex(String path);

// 日志
void log(Object msg);

// 提示
void toast(String text);

// 通知
void notify(String title, String text);

// 取顶部Activity
Activity getTopActivity();

// 上传设备步数
void uploadDeviceStep(long step);
```

## 朋友圈方法

```Java

// 上传文字
void uploadText(String content);
void uploadText(String content, String sdkId, String sdkAppName);
void uploadText(JSONObject jsonObj);

// 上传图文
void uploadTextAndPicList(String content, String picPath);
void uploadTextAndPicList(String content, String picPath, String sdkId, String sdkAppName);
void uploadTextAndPicList(String content, List<String> picPathList);
void uploadTextAndPicList(String content, List<String> picPathList, String sdkId, String sdkAppName);
void uploadTextAndPicList(JSONObject jsonObj);
```

---