
# QH-P22 实测：release(R8) 下 CapabilityRepository 状态流不更新（待启用不消失）
# 保守 keep：CapabilityRepository 与 DataStore 实例相关类
-keep class top.hnwen17.guard.platform.CapabilityRepository { *; }
-keep class top.hnwen17.guard.data.** { *; }
-keep class androidx.datastore.** { *; }
# kotlinx Flow/StateFlow 内部反射依赖
-keep class kotlinx.coroutines.flow.** { *; }
