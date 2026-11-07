"use client";

import { useMemo, useState } from "react";
import Link from "next/link";
import type { PatientListRow } from "@/lib/types";
import {
  formatDate,
  genderLabel,
  relativeTime,
} from "@/lib/format";

type SortKey = "recent" | "score" | "name";

export default function PatientsTable({
  initialPatients,
}: {
  initialPatients: PatientListRow[];
}) {
  const [query, setQuery] = useState("");
  const [sort, setSort] = useState<SortKey>("recent");

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    let list = initialPatients;
    if (q) {
      list = list.filter(
        (p) =>
          (p.name?.toLowerCase().includes(q) ?? false) ||
          p.email.toLowerCase().includes(q)
      );
    }
    list = [...list].sort((a, b) => {
      if (sort === "score") {
        const sa = a.latest_score ?? -1;
        const sb = b.latest_score ?? -1;
        return sb - sa;
      }
      if (sort === "name") {
        return (a.name ?? a.email).localeCompare(b.name ?? b.email, "ko");
      }
      // recent
      const ta = a.last_activity_at ? new Date(a.last_activity_at).getTime() : 0;
      const tb = b.last_activity_at ? new Date(b.last_activity_at).getTime() : 0;
      return tb - ta;
    });
    return list;
  }, [initialPatients, query, sort]);

  return (
    <div>
      <div className="mb-4 flex flex-wrap items-center gap-3">
        <div className="relative flex-1 min-w-[280px] max-w-md">
          <svg
            viewBox="0 0 24 24"
            fill="none"
            className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-[var(--color-ink-muted)]"
            stroke="currentColor"
            strokeWidth={2}
            strokeLinecap="round"
            strokeLinejoin="round"
          >
            <circle cx="11" cy="11" r="8" />
            <line x1="21" y1="21" x2="16.65" y2="16.65" />
          </svg>
          <input
            type="text"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="이름 또는 이메일로 검색"
            className="w-full rounded-lg border border-[var(--color-line)] bg-white py-2.5 pl-9 pr-3 text-sm outline-none transition focus:border-[var(--color-brand-green)] focus:ring-2 focus:ring-[var(--color-brand-green)]/15"
          />
        </div>

        <div className="flex items-center gap-1.5 rounded-lg border border-[var(--color-line)] bg-white p-1">
          <SortChip
            label="최근 활동순"
            active={sort === "recent"}
            onClick={() => setSort("recent")}
          />
          <SortChip
            label="점수순"
            active={sort === "score"}
            onClick={() => setSort("score")}
          />
          <SortChip
            label="이름순"
            active={sort === "name"}
            onClick={() => setSort("name")}
          />
        </div>
      </div>

      <div className="overflow-hidden rounded-2xl border border-[var(--color-line)] bg-white shadow-sm">
        <table className="w-full text-sm">
          <thead className="bg-[var(--color-canvas)] text-xs uppercase tracking-wider text-[var(--color-ink-muted)]">
            <tr>
              <th className="px-5 py-3 text-left font-semibold">이름</th>
              <th className="px-5 py-3 text-left font-semibold">이메일</th>
              <th className="px-5 py-3 text-left font-semibold">연령</th>
              <th className="px-5 py-3 text-left font-semibold">성별</th>
              <th className="px-5 py-3 text-left font-semibold">가입일</th>
              <th className="px-5 py-3 text-left font-semibold">최근 점수</th>
              <th className="px-5 py-3 text-left font-semibold">마지막 활동</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-[var(--color-line)]">
            {filtered.length === 0 && (
              <tr>
                <td colSpan={7} className="px-5 py-16 text-center">
                  <EmptyState query={query} hasPatients={initialPatients.length > 0} />
                </td>
              </tr>
            )}
            {filtered.map((p) => (
              <tr
                key={p.id}
                className="cursor-pointer transition hover:bg-[var(--color-brand-green-light)]/40"
              >
                <td className="px-5 py-4">
                  <Link
                    href={`/patients/${p.id}`}
                    className="font-medium text-[var(--color-ink)] hover:text-[var(--color-brand-green-dark)]"
                  >
                    {p.name ?? "이름 미입력"}
                  </Link>
                  {p.patient_number && (
                    <div className="text-xs text-[var(--color-ink-muted)]">
                      #{p.patient_number}
                    </div>
                  )}
                </td>
                <td className="px-5 py-4 text-[var(--color-ink-soft)]">
                  <Link href={`/patients/${p.id}`} className="block">
                    {p.email}
                  </Link>
                </td>
                <td className="px-5 py-4 text-[var(--color-ink-soft)]">
                  <Link href={`/patients/${p.id}`} className="block">
                    {p.age ?? "-"}
                  </Link>
                </td>
                <td className="px-5 py-4 text-[var(--color-ink-soft)]">
                  <Link href={`/patients/${p.id}`} className="block">
                    {genderLabel(p.gender)}
                  </Link>
                </td>
                <td className="px-5 py-4 text-[var(--color-ink-soft)]">
                  <Link href={`/patients/${p.id}`} className="block">
                    {formatDate(p.created_at)}
                  </Link>
                </td>
                <td className="px-5 py-4">
                  <Link href={`/patients/${p.id}`} className="block">
                    <ScoreBadge value={p.latest_score} />
                  </Link>
                </td>
                <td className="px-5 py-4 text-[var(--color-ink-soft)]">
                  <Link href={`/patients/${p.id}`} className="block">
                    {relativeTime(p.last_activity_at)}
                  </Link>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

function SortChip({
  label,
  active,
  onClick,
}: {
  label: string;
  active: boolean;
  onClick: () => void;
}) {
  return (
    <button
      onClick={onClick}
      className={`rounded-md px-3 py-1.5 text-xs font-medium transition ${
        active
          ? "bg-[var(--color-brand-green-light)] text-[var(--color-brand-green-dark)]"
          : "text-[var(--color-ink-muted)] hover:text-[var(--color-ink-soft)]"
      }`}
    >
      {label}
    </button>
  );
}

function ScoreBadge({ value }: { value: number | null }) {
  if (value == null) {
    return <span className="text-xs text-[var(--color-ink-muted)]">데이터 없음</span>;
  }
  let bg = "bg-[var(--color-brand-green-light)] text-[var(--color-brand-green-dark)]";
  if (value < 60) bg = "bg-red-50 text-[var(--color-danger)]";
  else if (value < 80) bg = "bg-amber-50 text-[var(--color-warn)]";
  return (
    <span
      className={`inline-flex h-7 min-w-[44px] items-center justify-center rounded-md px-2 text-sm font-semibold ${bg}`}
    >
      {value}
    </span>
  );
}

function EmptyState({
  query,
  hasPatients,
}: {
  query: string;
  hasPatients: boolean;
}) {
  if (query) {
    return (
      <div className="text-[var(--color-ink-muted)]">
        <div className="mb-1 text-sm font-medium text-[var(--color-ink-soft)]">
          검색 결과가 없습니다
        </div>
        <div className="text-xs">&ldquo;{query}&rdquo; 와 일치하는 환자가 없습니다.</div>
      </div>
    );
  }
  if (!hasPatients) {
    return (
      <div className="text-[var(--color-ink-muted)]">
        <div className="mb-1 text-sm font-medium text-[var(--color-ink-soft)]">
          등록된 환자가 없습니다
        </div>
        <div className="text-xs">
          환자가 앱에 가입하면 이 목록에 자동으로 나타납니다.
        </div>
      </div>
    );
  }
  return null;
}
