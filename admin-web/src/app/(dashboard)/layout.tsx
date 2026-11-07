import { redirect } from "next/navigation";
import Sidebar from "@/components/Sidebar";
import { createSupabaseServerClient } from "@/lib/supabase/server";

export default async function DashboardLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  const supabase = await createSupabaseServerClient();
  const {
    data: { user },
  } = await supabase.auth.getUser();

  if (!user) {
    redirect("/login");
  }

  return (
    <div className="flex h-screen overflow-hidden">
      <Sidebar doctorEmail={user.email} />
      <main className="flex-1 overflow-y-auto bg-[var(--color-canvas)]">
        {children}
      </main>
    </div>
  );
}
