"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { createSupabaseBrowserClient } from "@/lib/supabase/client";

type Props = {
  doctorEmail?: string | null;
};

export default function Sidebar({ doctorEmail }: Props) {
  const pathname = usePathname();
  const router = useRouter();

  async function handleLogout() {
    const supabase = createSupabaseBrowserClient();
    await supabase.auth.signOut();
    router.push("/login");
    router.refresh();
  }

  const isPatients = pathname?.startsWith("/patients");

  return (
    <aside className="flex h-screen w-60 flex-col border-r border-[var(--color-line)] bg-white">
      <div className="px-5 py-6">
        <div className="flex items-center gap-2.5">
          <div className="flex h-9 w-9 items-center justify-center rounded-xl bg-[var(--color-brand-green)] text-white">
            <svg
              viewBox="0 0 24 24"
              fill="none"
              className="h-5 w-5"
              stroke="currentColor"
              strokeWidth={2.2}
              strokeLinecap="round"
              strokeLinejoin="round"
            >
              <path d="M12 21s-7-4.35-9.5-9.5C1 7.5 4 4 7.5 4c1.7 0 3.3.8 4.5 2.2C13.2 4.8 14.8 4 16.5 4 20 4 23 7.5 21.5 11.5 19 16.65 12 21 12 21z" />
            </svg>
          </div>
          <div>
            <div className="text-sm font-semibold leading-tight">당당케어</div>
            <div className="text-[11px] text-[var(--color-ink-muted)]">
              의료진 콘솔
            </div>
          </div>
        </div>
      </div>

      <nav className="flex-1 px-3">
        <Link
          href="/patients"
          className={`flex items-center gap-2.5 rounded-lg px-3 py-2 text-sm font-medium transition ${
            isPatients
              ? "bg-[var(--color-brand-green-light)] text-[var(--color-brand-green-dark)]"
              : "text-[var(--color-ink-soft)] hover:bg-gray-50"
          }`}
        >
          <svg
            viewBox="0 0 24 24"
            fill="none"
            className="h-[18px] w-[18px]"
            stroke="currentColor"
            strokeWidth={2}
            strokeLinecap="round"
            strokeLinejoin="round"
          >
            <path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2" />
            <circle cx="9" cy="7" r="4" />
            <path d="M22 21v-2a4 4 0 0 0-3-3.87" />
            <path d="M16 3.13a4 4 0 0 1 0 7.75" />
          </svg>
          환자 목록
        </Link>

        <div className="mt-4 px-3 text-[10px] font-semibold uppercase tracking-wider text-[var(--color-ink-muted)]">
          예정 (v1.x)
        </div>
        <div className="mt-1 flex cursor-not-allowed items-center gap-2.5 rounded-lg px-3 py-2 text-sm text-[var(--color-ink-muted)]">
          <svg
            viewBox="0 0 24 24"
            fill="none"
            className="h-[18px] w-[18px]"
            stroke="currentColor"
            strokeWidth={2}
            strokeLinecap="round"
            strokeLinejoin="round"
          >
            <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
            <polyline points="14 2 14 8 20 8" />
          </svg>
          코칭 메모
        </div>
        <div className="mt-1 flex cursor-not-allowed items-center gap-2.5 rounded-lg px-3 py-2 text-sm text-[var(--color-ink-muted)]">
          <svg
            viewBox="0 0 24 24"
            fill="none"
            className="h-[18px] w-[18px]"
            stroke="currentColor"
            strokeWidth={2}
            strokeLinecap="round"
            strokeLinejoin="round"
          >
            <path d="M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9" />
            <path d="M10.3 21a1.94 1.94 0 0 0 3.4 0" />
          </svg>
          알림 설정
        </div>
      </nav>

      <div className="border-t border-[var(--color-line)] p-3">
        <div className="mb-2 px-2 text-xs">
          <div className="font-medium text-[var(--color-ink-soft)]">의료진</div>
          <div className="truncate text-[var(--color-ink-muted)]">
            {doctorEmail ?? "-"}
          </div>
        </div>
        <button
          onClick={handleLogout}
          className="flex w-full items-center justify-center gap-2 rounded-lg border border-[var(--color-line)] px-3 py-2 text-sm text-[var(--color-ink-soft)] transition hover:bg-gray-50"
        >
          <svg
            viewBox="0 0 24 24"
            fill="none"
            className="h-4 w-4"
            stroke="currentColor"
            strokeWidth={2}
            strokeLinecap="round"
            strokeLinejoin="round"
          >
            <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4" />
            <polyline points="16 17 21 12 16 7" />
            <line x1="21" y1="12" x2="9" y2="12" />
          </svg>
          로그아웃
        </button>
      </div>
    </aside>
  );
}
