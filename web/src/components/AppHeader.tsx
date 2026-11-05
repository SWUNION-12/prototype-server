import Link from "next/link";
import type { ReactNode } from "react";
import { SERVICE_NAME } from "@/lib/display";

type Props = {
  home: string;
  search?: ReactNode;
  actions?: ReactNode;
  nav?: ReactNode;
};

export function AppHeader({ home, search, actions, nav }: Props) {
  return (
    <header className="site">
      <div className="wrap">
        <div className="site-row">
          <Link className="logo" href={home}>
            {SERVICE_NAME}
          </Link>
          {search}
          <div className="site-actions">{actions}</div>
        </div>
        {nav && <nav className="site-nav">{nav}</nav>}
      </div>
    </header>
  );
}
