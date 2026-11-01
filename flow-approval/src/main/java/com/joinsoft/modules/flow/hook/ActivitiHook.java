package com.joinsoft.modules.flow.hook;

/**
 * 流程回调 Hook，兼容原 ActivitiHook 的方法签名。
 *
 * <p>实现类以流程定义 key 作为 bean 名称即可被自动调用，例如：
 * <pre>
 * &#64;Component("leave")
 * public class LeaveHook implements ActivitiHook { ... }
 * </pre>
 * </p>
 */
public interface ActivitiHook {

    default void onStart(String businessId, String processInstanceId) {
    }

    default void onComplete(String businessId, String processInstanceId, String taskName, Long flow) {
    }

    default void onFinish(String businessId, String processInstanceId, Long flow) {
    }

    default void onJump(String businessId, String processInstanceId, String taskName, Long flow) {
    }

    default void onBack(String businessId, String processInstanceId, String taskName, Long flow) {
    }

    default void onRepeal(String businessId, String processInstanceId) {
    }

    default void onTerminal(String businessId, String processInstanceId) {
    }
}
