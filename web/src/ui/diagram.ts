/** The plain data `DiagramView` draws, and the words it reads out (ADR-0117). */

/** One box of a `DiagramView`. Everything is plain data, shown as text. */
export interface DiagramNode {
  id: string;
  /** The box's name, one line. */
  label: string;
  /** What kind of thing it is, in words ("Call a service"). Shown above the label. */
  kind?: string;
  /** A second line under the label ("inventory POST /reserve"). */
  detail?: string;
  /** Something is wrong with it: shown in words and colour, and read out with the node. */
  state?: 'error' | 'warning';
  /** Why, for a node with a state. */
  reason?: string;
}

/** One arrow of a `DiagramView`, from `source` to `target`. */
export interface DiagramEdge {
  id: string;
  source: string;
  target: string;
  label?: string;
  /** Drawn dashed: a secondary path. Its meaning belongs in its label. */
  dashed?: boolean;
  /** Line thickness, as a CSS custom property. It does not move a box. */
  weight?: number;
  /** Something can be inserted on this arrow: it offers the diagram's `insertChoices`. */
  insertable?: boolean;
}

/** One thing a `DiagramView` offers to insert on an arrow ("Call a service"). */
export interface DiagramChoice {
  value: string;
  label: string;
  /** A heading the choice is listed under. */
  group?: string;
}

/** One action in a box's menu. The diagram only reports it: the caller changes the nodes. */
export interface DiagramAction {
  id: string;
  label: string;
  /** Why it cannot be taken here. It stays listed, with the reason, rather than disappearing. */
  disabledReason?: string;
  /** Destroys something: shown in the danger colour. The label still says so in words. */
  danger?: boolean;
}

/** What positions depend on: which nodes exist and how they connect. A new label moves nothing. */
export function layoutSignature(nodes: DiagramNode[], edges: DiagramEdge[], direction: string): string {
  const arrows = edges.map((e) => `${e.source}>${e.target}`).join('|');
  return `${direction}#${nodes.map((n) => n.id).join('|')}#${arrows}`;
}

/**
 * The accessible name of a node: kind, label and detail, the labels of the arrows into it (which a
 * screen reader otherwise never meets), and any problem.
 */
export function nodeName(n: DiagramNode, via: string[] = []): string {
  let name = [n.kind, n.label, n.detail].filter(Boolean).join(', ');
  if (via.length) name += `. Via ${via.join('; ')}`;
  if (n.state) {
    const problem = n.state === 'error' ? 'Invalid' : 'Warning';
    const why = n.reason ? `: ${n.reason}` : '';
    name += `. ${problem}${why}`;
  }
  return name;
}
