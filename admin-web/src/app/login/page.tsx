"use client";

import { Suspense, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { createSupabaseBrowserClient } from "@/lib/supabase/client";

// useSearchParams()는 CSR bailout을 일으켜 프리렌더에서 Suspense 경계가 필요하다.
// 경계 없이 두면 `next build`가 /login 프리렌더 단계에서 실패한다.
export default function LoginPage() {
  return (
    <Suspense fallback={null}>
      <LoginForm />
    </Suspense>
  );
}

function LoginForm() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const redirectTo = searchParams.get("redirect") || "/patients";

  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setLoading(true);
    setError(null);

    const supabase = createSupabaseBrowserClient();
    const { error: signInError } = await supabase.auth.signInWithPassword({
      email,
      password,
    });

    if (signInError) {
      setError(
        signInError.message === "Invalid login credentials"
          ? "이메일 또는 비밀번호가 올바르지 않습니다."
          : signInError.message
      );
      setLoading(false);
      return;
    }

    router.push(redirectTo);
    router.refresh();
  }

  return (
    <main className="flex min-h-screen items-center justify-center bg-[var(--color-canvas)] px-4">
      <div className="w-full max-w-md">
        <div className="mb-8 text-center">
          <div className="mx-auto mb-4 flex h-14 w-14 items-center justify-center rounded-2xl bg-[var(--color-brand-green)] text-white shadow-sm">
            <svg
              viewBox="0 0 24 24"
              fill="none"
              className="h-7 w-7"
              stroke="currentColor"
              strokeWidth={2.2}
              strokeLinecap="round"
              strokeLinejoin="round"
            >
              <path d="M12 21s-7-4.35-9.5-9.5C1 7.5 4 4 7.5 4c1.7 0 3.3.8 4.5 2.2C13.2 4.8 14.8 4 16.5 4 20 4 23 7.5 21.5 11.5 19 16.65 12 21 12 21z" />
            </svg>
          </div>
          <h1 className="text-2xl font-semibold tracking-tight text-[var(--color-ink)]">
            당당케어 의료진 콘솔
          </h1>
          <p className="mt-1 text-sm text-[var(--color-ink-muted)]">
            등록된 의료진 계정으로 로그인하세요
          </p>
        </div>

        <form
          onSubmit={handleSubmit}
          className="rounded-2xl border border-[var(--color-line)] bg-white p-7 shadow-sm"
        >
          <label className="mb-4 block">
            <span className="mb-1.5 block text-sm font-medium text-[var(--color-ink-soft)]">
              이메일
            </span>
            <input
              type="email"
              required
              autoComplete="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              className="w-full rounded-lg border border-[var(--color-line)] bg-white px-3.5 py-2.5 text-sm outline-none transition focus:border-[var(--color-brand-green)] focus:ring-2 focus:ring-[var(--color-brand-green)]/20"
              placeholder="doctor@example.com"
            />
          </label>

          <label className="mb-2 block">
            <span className="mb-1.5 block text-sm font-medium text-[var(--color-ink-soft)]">
              비밀번호
            </span>
            <input
              type="password"
              required
              autoComplete="current-password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              className="w-full rounded-lg border border-[var(--color-line)] bg-white px-3.5 py-2.5 text-sm outline-none transition focus:border-[var(--color-brand-green)] focus:ring-2 focus:ring-[var(--color-brand-green)]/20"
              placeholder="••••••••"
            />
          </label>

          {error && (
            <div className="mt-3 rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-sm text-[var(--color-danger)]">
              {error}
            </div>
          )}

          <button
            type="submit"
            disabled={loading}
            className="mt-5 w-full rounded-lg bg-[var(--color-brand-green)] py-2.5 text-sm font-semibold text-white transition hover:bg-[var(--color-brand-green-dark)] disabled:opacity-50"
          >
            {loading ? "로그인 중..." : "로그인"}
          </button>

          <p className="mt-4 text-center text-xs text-[var(--color-ink-muted)]">
            계정이 없으신가요? 관리자에게 문의하세요.
          </p>
        </form>

        <p className="mt-6 text-center text-xs text-[var(--color-ink-muted)]">
          © 2026 당당케어 · 의료진 전용 콘솔
        </p>
      </div>
    </main>
  );
}
