export type Patient = {
  id: string;
  email: string;
  patient_number: string | null;
  name: string | null;
  gender: "M" | "F" | null;
  age: number | null;
  height_cm: number | null;
  weight_kg: number | null;
  diabetes_diagnosed_at: string | null;
  onboarding_completed: boolean;
  created_at: string;
};

export type PatientListRow = Patient & {
  latest_score: number | null;
  latest_score_date: string | null;
  last_activity_at: string | null;
};

export type DailyLog = {
  id: string;
  user_id: string;
  log_date: string;
  fasting_glucose: number | null;
  alcohol: boolean | null;
  dinner_before_7pm: boolean | null;
  no_food_after_dinner: boolean | null;
  input_method: string | null;
  created_at: string;
};

export type LifestyleScore = {
  id: string;
  user_id: string;
  score_date: string;
  total_score: number | null;
  glucose_score: number | null;
  steps_score: number | null;
  sleep_score: number | null;
};

export type WatchData = {
  id: string;
  user_id: string;
  log_date: string;
  steps: number;
  sleep_total_minutes: number | null;
  avg_heart_rate: number | null;
};

export type AIFeedback = {
  id: string;
  user_id: string;
  feedback_date: string;
  daily_message: string | null;
  encouragement: string | null;
};

export type PatientDetail = {
  patient: Patient;
  daily_logs: DailyLog[];
  scores: LifestyleScore[];
  watch_data: WatchData[];
  ai_feedbacks: AIFeedback[];
};
