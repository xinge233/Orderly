package com.orderly.host.contract;

import com.orderly.kernel.RiskLevel;

import java.util.List;

/**
 * 模块动作契约。
 *
 * @param id 全局动作 ID
 * @param title 显示名称
 * @param description 说明
 * @param risk 风险等级
 * @param confirmationRequired 是否要求计划确认
 * @param confirmationPhrase 删除类动作的强确认词
 * @param parameters 参数
 */
public record ActionContract(
        String id,
        String title,
        String description,
        RiskLevel risk,
        boolean confirmationRequired,
        String confirmationPhrase,
        List<ParameterContract> parameters
) {
    public ActionContract {
        parameters = List.copyOf(parameters);
    }
}
