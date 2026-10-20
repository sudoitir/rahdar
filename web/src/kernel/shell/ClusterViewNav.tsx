import { useCan } from '../auth/useCan.ts';
import { useFeatures } from '../features.ts';
import { navGroups } from '../registry.ts';
import { useClusterSystem } from './useClusterSystem.ts';
import { NavGroup } from './NavGroup.tsx';
import { NavItem } from './NavItem.tsx';
import styles from './ClusterViewNav.module.css';

/**
 * The per-cluster view nav (ADR-0034), grouped (ADR-0070). Each enabled feature contributes its
 * views to one of the kernel's groups; a group with no enabled view is not shown. Expanded, a group
 * has a visible title; collapsed, a rule in the title's place, and its title stays available to a screen reader. The title
 * names its group but is not a heading: the page's headings are the page's own, starting at its one h1.
 * A view the operator lacks the read permission for stays listed and disabled, with the reason:
 * a missing entry would read as a product that cannot do it. Only rendered while a cluster is the
 * active route.
 */
export function ClusterViewNav({ clusterId, collapsed }: Readonly<{ clusterId: string; collapsed: boolean }>) {
  const groups = navGroups(useFeatures(), useClusterSystem(clusterId));
  // While grants are still loading the entries are offered: refusing before the answer arrives
  // would claim something that has not been checked.
  const { canAnywhere, loading } = useCan();

  return (
    <nav aria-label="Cluster views" className={styles.nav}>
      {groups.map((group) => (
        <NavGroup key={group.id} label={group.label} collapsed={collapsed}>
          {group.items.map((item) => (
            <NavItem
              key={item.path}
              to={`/clusters/${clusterId}/${item.path}`}
              label={item.label}
              collapsed={collapsed}
              leading={<item.icon size={18} stroke={1.5} />}
              trailing={item.Badge ? <item.Badge clusterId={clusterId} /> : undefined}
              disabledReason={
                item.permission && !loading && !canAnywhere(item.permission, clusterId)
                  ? `Opening ${item.label} needs the ${item.permission} permission on this cluster.`
                  : undefined
              }
            />
          ))}
        </NavGroup>
      ))}
    </nav>
  );
}
