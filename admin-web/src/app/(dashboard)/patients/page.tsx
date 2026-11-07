import { createSupabaseAdminClient } from "@/lib/supabase/server";
import type { Patient, PatientListRow } from "@/lib/types";
import PatientsTable from "./PatientsTable";

export const dynamic = "force-dynamic";

async function loadPatients(): Promise<PatientListRow[]> {
  const supabase = createSupabaseAdminClient();

  const { data: users, error: usersError } = await supabase
    .from("users")
    .select(
      "id, email, patient_number, name, gender, age, height_cm, weight_kg, diabetes_diagnosed_at, onboarding_completed, created_at"
    )
    .order("created_at", { ascending: false })
    .limit(500);

  if (usersError) {
    console.error("loadPatients: users", usersError);
    return [];
  }

  const patients = (users ?? []) as Patient[];
  if (patients.length === 0) return [];

  const ids = patients.map((p) => p.id);

  // 각 환자의 최근 점수 1건 — 큰 쿼리 한 번으로 받아 후처리
  const { data: scoresRaw } = await supabase
    .from("lifestyle_scores")
    .select("user_id, score_date, total_score")
    .in("user_id", ids)
    .order("score_date", { ascending: false })
    .limit(2000);

  const latestScoreByUser = new Map<
    string,
    { score: number | null; date: string }
  >();
  for (const row of scoresRaw ?? []) {
    if (!latestScoreByUser.has(row.user_id)) {
      latestScoreByUser.set(row.user_id, {
        score: row.total_score ?? null,
        date: row.score_date,
      });
    }
  }

  // 마지막 활동 — daily_logs 의 가장 최근 created_at
  const { data: logsRaw } = await supabase
    .from("daily_logs")
    .select("user_id, created_at, updated_at")
    .in("user_id", ids)
    .order("updated_at", { ascending: false })
    .limit(2000);

  const lastActivityByUser = new Map<string, string>();
  for (const row of logsRaw ?? []) {
    if (!lastActivityByUser.has(row.user_id)) {
      lastActivityByUser.set(row.user_id, row.updated_at ?? row.created_at);
    }
  }

  return patients.map<PatientListRow>((p) => {
    const score = latestScoreByUser.get(p.id);
    return {
      ...p,
      latest_score: score?.score ?? null,
      latest_score_date: score?.date ?? null,
      last_activity_at: lastActivityByUser.get(p.id) ?? null,
    };
  });
}

export default async function PatientsPage() {
  const patients = await loadPatients();

  return (
    <div className="px-8 py-7">
      <div className="mb-6 flex items-end justify-between">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight text-[var(--color-ink)]">
            환자 목록
          </h1>
          <p className="mt-1 text-sm text-[var(--color-ink-muted)]">
            총 {patients.length}명 · 환자를 클릭하면 상세 데이터를 볼 수 있습니다
          </p>
        </div>
      </div>

      <PatientsTable initialPatients={patients} />
    </div>
  );
}
