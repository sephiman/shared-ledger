export interface ManualRouteLink {
  to: string;
  labelKey: string;
}

export interface ManualSection {
  id: string;
  heading: string;
  paragraphs: string[];
  bullets?: Array<{
    term?: string;
    text: string;
  }>;
  tip?: string;
}

export interface ManualChapter {
  id: string;
  title: string;
  summary: string;
  /** Optional app route and label key for a quick "Open …" link in the chapter header. */
  route?: ManualRouteLink;
  sections: ManualSection[];
}
