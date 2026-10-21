import { Page } from '../../ui/Page.tsx';
import { PageHeader } from '../../ui/PageHeader.tsx';
import { useSlot } from '../slots.ts';
import classes from './Views.module.css';

/** The landing page, with no cluster open: the estate, which is what the features contribute for it. */
export function HomeView() {
  const content = useSlot('home.estate');
  return (
    <div className={classes.page}>
      <Page>
        <PageHeader
          title="All clusters"
          description="Every cluster of every connected system, and their subscriptions. Open a cluster, or switch between clusters at the top of the sidebar."
        />
        {content.map(({ id, Component }) => (
          <Component key={id} />
        ))}
      </Page>
    </div>
  );
}
