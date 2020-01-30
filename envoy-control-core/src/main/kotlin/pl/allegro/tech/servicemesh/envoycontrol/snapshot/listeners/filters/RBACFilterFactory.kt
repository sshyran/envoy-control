package pl.allegro.tech.servicemesh.envoycontrol.snapshot.listeners.filters

import com.google.protobuf.Any
import io.envoyproxy.envoy.api.v2.route.HeaderMatcher
import io.envoyproxy.envoy.config.filter.http.rbac.v2.RBAC as RBACFilter
import io.envoyproxy.envoy.config.rbac.v2.Permission
import io.envoyproxy.envoy.config.rbac.v2.Policy
import io.envoyproxy.envoy.config.rbac.v2.Principal
import io.envoyproxy.envoy.config.rbac.v2.RBAC
import pl.allegro.tech.servicemesh.envoycontrol.groups.Group
import io.envoyproxy.envoy.config.filter.network.http_connection_manager.v2.HttpFilter

import pl.allegro.tech.servicemesh.envoycontrol.groups.Incoming
import pl.allegro.tech.servicemesh.envoycontrol.groups.IncomingEndpoint
import pl.allegro.tech.servicemesh.envoycontrol.groups.PathMatchingType
import pl.allegro.tech.servicemesh.envoycontrol.logger
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.IncomingPermissionsProperties

class RBACFilterFactory(
    private val properties: IncomingPermissionsProperties
) {
    companion object {
        private val logger by logger()
    }

    fun getRules(serviceName: String, incomingPermissions: Incoming): RBAC {
        val clientToPolicy = mutableMapOf<String, Policy>()
        incomingPermissions.endpoints.forEach { incomingEndpoint ->
            if (incomingEndpoint.clients.isEmpty()) {
                logger.error("An incoming endpoint definition for $serviceName does not have any clients defined." +
                        "It means that noone will be able to contact that endpoint.")
                return@forEach
            }

            val policy = Policy.newBuilder()
            val clientName = incomingEndpoint.clients.joinToString(",")

            val pathPermission = Permission.newBuilder().setHeader(getPathMatcher(incomingEndpoint))
            val methodPermissions = Permission.newBuilder()
            val combinedPermissions = Permission.newBuilder()

            val principals = incomingEndpoint.clients.map(this::mapClientToPrincipal)
            policy.addAllPrincipals(principals)

            if (incomingEndpoint.methods.isNotEmpty()) {
                val methodPermissionSet = Permission.Set.newBuilder()
                val methodPermissionsList = incomingEndpoint.methods.map(this::mapMethodToHeaderMatcher)
                methodPermissionSet.addAllRules(methodPermissionsList)
                methodPermissions.setOrRules(methodPermissionSet.build())
                combinedPermissions.setAndRules(
                        Permission.Set.newBuilder().addAllRules(listOf(
                                pathPermission.build(),
                                methodPermissions.build()
                        ))
                )
            } else {
                combinedPermissions.setAndRules(
                        Permission.Set.newBuilder().addAllRules(listOf(pathPermission.build()))
                )
            }

            policy.addPermissions(combinedPermissions)
            clientToPolicy[clientName] = policy.build()
        }

        val rbac = RBAC.newBuilder()
                .setAction(RBAC.Action.ALLOW)
                .putAllPolicies(clientToPolicy)
                .build()
        return rbac
    }

    private fun mapMethodToHeaderMatcher(method: String): Permission {
        val methodMatch = HeaderMatcher.newBuilder().setName(":method").setExactMatch(method).build()
        return Permission.newBuilder().setHeader(methodMatch).build()
    }

    private fun mapClientToPrincipal(client: String): Principal {
        val clientMatch = HeaderMatcher.newBuilder()
                .setName(properties.clientIdentityHeader).setExactMatch(client).build()
        return Principal.newBuilder().setHeader(clientMatch).build()
    }

    private fun getPathMatcher(incomingEndpoint: IncomingEndpoint): HeaderMatcher {
        return when (incomingEndpoint.pathMatchingType) {
            PathMatchingType.PATH -> HeaderMatcher.newBuilder()
                    .setName(":path").setExactMatch(incomingEndpoint.path).build()
            PathMatchingType.PATH_PREFIX -> HeaderMatcher.newBuilder()
                    .setName(":path").setPrefixMatch(incomingEndpoint.path).build()
        }
    }

    fun createHttpFilter(group: Group): HttpFilter? {
        return if (properties.enabled) {
            val rules = getRules(group.serviceName, group.proxySettings.incoming)
            val rbacFilter = RBACFilter.newBuilder().setRules(rules).build()
            HttpFilter.newBuilder().setName("envoy.filters.http.rbac").setTypedConfig(Any.pack(rbacFilter)).build()
        } else {
            null
        }
    }
}
