package dynamicscripts

import com.alibaba.tesla.appmanager.server.dynamicscript.handler.ComponentHandler

/**
 * 默认 AppMeta Internal Addon Groovy Handler
 *
 * @author yaoxing.gyx@alibaba-inc.com
 */
class DefaultInternalAddonDevelopmentMetaHandler implements ComponentHandler {

    /**
     * Handler 元信息
     */
    public static final String KIND = "COMPONENT"
    public static final String NAME = "development.meta.internal.addon.sreworks.io/v1beta1"
    public static final Integer REVISION = 1

    /**
     * 获取 `INTERNAL_ADDON_BUILD` 类型下的映射名称
     *
     * @return 示例：`AbmChartDefault`
     */
    @Override
    String buildScriptName() {
        return "development.meta.internal.addon.sreworks.io/v1beta1/build"
    }

    /**
     * 获取 `INTERNAL_ADDON_DEPLOY` 类型下的映射名称
     *
     * @return 示例：`JobDefault` / `HelmDefault`
     */
    @Override
    String deployScriptName() {
        return "development.meta.internal.addon.sreworks.io/v1beta1/deploy"
    }

    /**
     * 获取 `COMPONENT_DESTROY` 类型下的映射名称
     *
     * @return 示例：`HelmDefault`
     */
    @Override
    String destroyName() {
        return ""
    }

    /**
     * 获取状态监听类型
     *
     * @return 返回 `KUBERNETES_INFORMER` 或 `CRON`
     */
    @Override
    String watchKind() {
        return ""
    }

    /**
     * 如果 `watchKind` 返回 `KUBERNETES_INFORMER`，则对应 `INTERNAL_ADDON_WATCH_KUBERNETES_INFORMER` 类型下的映射名称
     * <p>
     * 如果 `watchKind` 返回 `CRON`，则对应 `INTERNAL_ADDON_WATCH_CRON` 类型下的映射名称
     *
     * @return 返回对应类型下的映射名称
     */
    @Override
    String watchScriptName() {
        return ""
    }
}