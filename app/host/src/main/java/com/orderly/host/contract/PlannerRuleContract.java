package com.orderly.host.contract;

import java.util.Map;
import java.util.Set;

/**
 * 由模块声明的本地规则计划匹配。
 *
 * @param id 全局规则 ID
 * @param actionId 规则生成的模块动作
 * @param pattern 完整匹配正则
 * @param argumentGroups 动作参数到正则捕获组序号的映射
 * @param pathArguments 需要按工作区相对路径清洗的参数
 * @param wholeGoal 是否只在整条目标上匹配
 */
public record PlannerRuleContract(
        String id,
        String actionId,
        String pattern,
        Map<String, Integer> argumentGroups,
        Set<String> pathArguments,
        boolean wholeGoal
) {
    public PlannerRuleContract {
        argumentGroups = Map.copyOf(argumentGroups);
        pathArguments = Set.copyOf(pathArguments);
    }
}
