**GroupInfo 是一个 Kotlin data class，只有 4 个字段：**

| 字段          | 类型       | 样例值                              | 含义                     |
|---------------|------------|-------------------------------------|--------------------------|
| `name`        | String     | `四川大学新生交流群`                | 群名称（显示名称）       |
| `remark`      | String     | `(空字符串)`                        | 群备注（用户自己设置的备注，通常为空） |
| `roomId`      | String     | `21225364064@chatroom`              | 群的唯一标识（微信内部 roomId，带 @chatroom 后缀） |
| `groupData`   | GroupData  | `GroupData(roomId=..., memberIds=[...])` | 群的详细数据对象，包含 roomId 和成员列表（memberIds） |