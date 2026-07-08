import { useEffect, useMemo, useState } from "react";
import { Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { useActiveHousehold, useAuth } from "@/auth/AuthContext";
import { useChangePassword, useDeleteHousehold, useHousehold, useHouseholdMembers, useInvitations, useIssueInvitation, useRevokeInvitation, useSetDefaultHousehold, useUpdateHousehold, useWipeHouseholdData } from "@/api/settings";
import { useCategories, useDeleteCustomCategory, type Category } from "@/api/catalog";
import { Button, Card, CardBody, CardHeader, FieldError, Input, Label, Select } from "@/components/ui/primitives";
import { asApiError } from "@/api/client";
import { categoryIcon } from "@/lib/categoryGroup";
import { CreateHouseholdDialog } from "@/features/household/CreateHouseholdDialog";
import { CustomCategoryDialog } from "./CustomCategoryDialog";
import { NotificationsCard } from "./NotificationsCard";
import { AutoSnapshotCard } from "./AutoSnapshotCard";
import { getCurrencyOptions } from "@/lib/currency";
import { useBankConfig } from "@/api/banks";
import { BanksCard } from "@/features/banks/BanksCard";

export function SettingsPage() {
  const { t, i18n } = useTranslation();
  const { user, refresh, activeHouseholdId, setActiveHouseholdId } = useAuth();
  const household = useActiveHousehold();
  const setDefault = useSetDefaultHousehold();
  const deleteHh = useDeleteHousehold();
  const [createOpen, setCreateOpen] = useState(false);
  const [deleteError, setDeleteError] = useState<string | null>(null);
  const [deleteTargetId, setDeleteTargetId] = useState<string | null>(null);
  const [deleteConfirm, setDeleteConfirm] = useState("");
  const { data: hh } = useHousehold(household.householdId);
  const updateHh = useUpdateHousehold(household.householdId);
  const isOwner = household.role === "owner";

  const [name, setName] = useState<string>("");
  const [currency, setCurrency] = useState<string>("");
  const [defaultLocale, setDefaultLocale] = useState<string>("");
  const currencyOptions = useMemo(() => {
    const opts = getCurrencyOptions(i18n.language);
    // Keep a legacy/non-listed code (e.g. one stored before ISO validation) visible in the dropdown.
    const existing = hh?.currency;
    if (existing && !opts.some((o) => o.code === existing)) {
      return [{ code: existing, label: existing }, ...opts];
    }
    return opts;
  }, [i18n.language, hh?.currency]);

  useEffect(() => {
    if (hh) {
      setName(hh.name);
      setCurrency(hh.currency);
      setDefaultLocale(hh.defaultLocale);
    }
  }, [hh]);

  const { data: members = [] } = useHouseholdMembers(household.householdId);
  const { data: invitations = [] } = useInvitations(household.householdId);
  const issue = useIssueInvitation(household.householdId);
  const revoke = useRevokeInvitation(household.householdId);
  const [inviteEmail, setInviteEmail] = useState("");
  const [inviteRole, setInviteRole] = useState<"owner" | "member">("member");
  const [issuedToken, setIssuedToken] = useState<string | null>(null);

  const changePassword = useChangePassword();
  const [currentPw, setCurrentPw] = useState("");
  const [newPw, setNewPw] = useState("");
  const [pwMsg, setPwMsg] = useState<string | null>(null);
  const [currentPwError, setCurrentPwError] = useState<string | null>(null);
  const [newPwError, setNewPwError] = useState<string | null>(null);
  const [hhErrors, setHhErrors] = useState<{ name?: string; currency?: string }>({});

  const wipe = useWipeHouseholdData(household.householdId);
  const [wipeOpen, setWipeOpen] = useState(false);
  const [wipeConfirm, setWipeConfirm] = useState("");
  const [wipeMsg, setWipeMsg] = useState<string | null>(null);

  const { data: bankConfig } = useBankConfig(household.householdId);
  const { data: allCategories = [] } = useCategories(household.householdId);
  const customCategories = allCategories.filter((c) => c.custom);
  const deleteCustom = useDeleteCustomCategory(household.householdId);
  const [customDialogOpen, setCustomDialogOpen] = useState(false);
  const [customEditing, setCustomEditing] = useState<Category | null>(null);
  const [customDeleteTarget, setCustomDeleteTarget] = useState<string | null>(null);
  const [customDeleteConfirm, setCustomDeleteConfirm] = useState("");
  const [customDeleteError, setCustomDeleteError] = useState<string | null>(null);

  return (
    <div className="space-y-4">
      <h1 className="text-xl font-semibold">{t("settings.title")}</h1>

      <Card>
        <CardHeader>
          <p className="font-medium">{t("import.title")}</p>
          <p className="mt-1 text-sm text-gray-500 dark:text-gray-400">{t("import.settings_description")}</p>
        </CardHeader>
        <CardBody>
          <Link to="/settings/import" className="text-sm text-primary">{t("import.open")}</Link>
        </CardBody>
      </Card>

      <Card>
        <CardHeader>
          <p className="font-medium">{t("auth.change_password")}</p>
        </CardHeader>
        <CardBody className="space-y-3">
          <div>
            <Input
              type="password"
              placeholder={t("auth.current_password")}
              value={currentPw}
              invalid={!!currentPwError}
              onChange={(e) => { setCurrentPw(e.target.value); if (currentPwError) setCurrentPwError(null); }}
            />
            <FieldError message={currentPwError} />
          </div>
          <div>
            <Input
              type="password"
              placeholder={t("auth.new_password")}
              value={newPw}
              invalid={!!newPwError}
              onChange={(e) => { setNewPw(e.target.value); if (newPwError) setNewPwError(null); }}
            />
            <FieldError message={newPwError} />
          </div>
          {pwMsg && <FieldError message={pwMsg} />}
          <Button
            onClick={async () => {
              setPwMsg(null);
              let invalid = false;
              if (!currentPw) { setCurrentPwError(t("errors.field_required")); invalid = true; }
              if (!newPw) { setNewPwError(t("errors.field_required")); invalid = true; }
              if (invalid) return;
              try {
                await changePassword.mutateAsync({ currentPassword: currentPw, newPassword: newPw });
                setCurrentPw("");
                setNewPw("");
                setPwMsg(t("common.save"));
              } catch (err) {
                const api = asApiError(err);
                setPwMsg(t(`errors.${api.code}`, api.message));
              }
            }}
          >
            {t("common.save")}
          </Button>
        </CardBody>
      </Card>

      {user && (
        <Card>
          <CardHeader>
            <div className="flex items-start justify-between gap-3">
              <div>
                <p className="font-medium">{t("settings.households_title")}</p>
                <p className="mt-1 text-sm text-gray-500 dark:text-gray-400">{t("settings.households_description")}</p>
              </div>
              <Button variant="secondary" onClick={() => setCreateOpen(true)}>
                {t("settings.create_household")}
              </Button>
            </div>
          </CardHeader>
          <CardBody>
            <ul className="space-y-2">
              {user.households.map((h) => {
                const isDefault = h.householdId === user.defaultHouseholdId;
                const isActive = h.householdId === activeHouseholdId;
                const isOwnerHere = h.role === "owner";
                const isDeleting = deleteTargetId === h.householdId;
                return (
                  <li key={h.householdId} className="rounded-md border border-border p-3 dark:border-gray-700">
                    <div className="flex flex-wrap items-center justify-between gap-2">
                      <div className="min-w-0 break-words">
                        <span className="font-medium">{h.name}</span>
                        <span className="ml-2 text-xs text-gray-500 dark:text-gray-400">{h.currency}</span>
                        {isDefault && (
                          <span className="ml-2 rounded bg-amber-100 px-1.5 py-0.5 text-xs text-amber-800 dark:bg-amber-900/50 dark:text-amber-200">
                            {t("settings.default_badge")}
                          </span>
                        )}
                        {isActive && (
                          <span className="ml-2 rounded bg-sky-100 px-1.5 py-0.5 text-xs text-sky-800 dark:bg-sky-900/50 dark:text-sky-200">
                            {t("settings.active_badge")}
                          </span>
                        )}
                      </div>
                      <div className="flex flex-wrap gap-1">
                        {!isActive && (
                          <Button
                            variant="ghost"
                            onClick={() => setActiveHouseholdId(h.householdId)}
                          >
                            {t("household.switch_to")}
                          </Button>
                        )}
                        <Button
                          variant="ghost"
                          disabled={isDefault || setDefault.isPending}
                          onClick={async () => {
                            await setDefault.mutateAsync(h.householdId);
                            await refresh();
                          }}
                        >
                          {t("settings.set_default")}
                        </Button>
                        {isOwnerHere && (
                          <Button
                            variant="ghost"
                            disabled={isDefault || deleteHh.isPending}
                            title={isDefault ? t("settings.delete_household_blocked") : undefined}
                            onClick={() => {
                              setDeleteError(null);
                              setDeleteConfirm("");
                              setDeleteTargetId(h.householdId);
                            }}
                            className="text-red-600 dark:text-red-400"
                          >
                            {t("settings.delete_household")}
                          </Button>
                        )}
                      </div>
                    </div>
                    {isDeleting && (
                      <div className="mt-3 space-y-3 rounded-md border border-red-300 bg-red-50 p-3 dark:border-red-800 dark:bg-red-950/30">
                        <p className="text-sm text-red-800 dark:text-red-300">{t("settings.delete_household_confirm")}</p>
                        <div>
                          <Label>{t("settings.wipe_confirm_prompt")}</Label>
                          <Input
                            value={deleteConfirm}
                            onChange={(e) => { setDeleteConfirm(e.target.value); if (deleteError) setDeleteError(null); }}
                            placeholder="delete"
                            autoFocus
                          />
                        </div>
                        {deleteError && <FieldError message={deleteError} />}
                        <div className="flex gap-2">
                          <Button
                            variant="danger"
                            disabled={deleteConfirm !== "delete" || deleteHh.isPending}
                            onClick={async () => {
                              setDeleteError(null);
                              try {
                                if (isActive) {
                                  const fallback = user.households.find((x) => x.householdId !== h.householdId);
                                  if (fallback) setActiveHouseholdId(fallback.householdId);
                                }
                                await deleteHh.mutateAsync(h.householdId);
                                await refresh();
                                setDeleteTargetId(null);
                                setDeleteConfirm("");
                              } catch (err) {
                                const api = asApiError(err);
                                setDeleteError(t(`errors.${api.code}`, api.message));
                              }
                            }}
                          >
                            {t("settings.delete_household")}
                          </Button>
                          <Button
                            variant="secondary"
                            onClick={() => { setDeleteTargetId(null); setDeleteConfirm(""); setDeleteError(null); }}
                          >
                            {t("common.cancel")}
                          </Button>
                        </div>
                      </div>
                    )}
                  </li>
                );
              })}
            </ul>
            <p className="mt-3 text-xs text-gray-500 dark:text-gray-400">{t("settings.delete_household_blocked")}</p>
            {deleteError && <FieldError message={deleteError} />}
          </CardBody>
        </Card>
      )}

      {hh && (
        <Card>
          <CardHeader>
            <p className="font-medium">{t("settings.household_settings")}</p>
            <p className="mt-1 text-sm text-gray-500 dark:text-gray-400">{t("settings.household_settings_description")}</p>
          </CardHeader>
          <CardBody className="space-y-3">
            <div className="grid grid-cols-1 gap-3 md:grid-cols-3">
              <div>
                <Label>{t("auth.household_name")}</Label>
                <Input
                  value={name || hh.name}
                  invalid={!!hhErrors.name}
                  onChange={(e) => { setName(e.target.value); if (hhErrors.name) setHhErrors({ ...hhErrors, name: undefined }); }}
                  disabled={!isOwner}
                />
                <FieldError message={hhErrors.name} />
              </div>
              <div>
                <Label>{t("common.currency")}</Label>
                <Select
                  value={currency || hh.currency}
                  invalid={!!hhErrors.currency}
                  onChange={(e) => { setCurrency(e.target.value); if (hhErrors.currency) setHhErrors({ ...hhErrors, currency: undefined }); }}
                  disabled={!isOwner}
                >
                  {currencyOptions.map((opt) => (
                    <option key={opt.code} value={opt.code}>{opt.label}</option>
                  ))}
                </Select>
                <FieldError message={hhErrors.currency} />
              </div>
              <div>
                <Label>{t("common.language")}</Label>
                <Select value={defaultLocale || hh.defaultLocale} onChange={(e) => setDefaultLocale(e.target.value)} disabled={!isOwner}>
                  <option value="en">English</option>
                  <option value="es">Español</option>
                </Select>
              </div>
            </div>
            {isOwner && (
              <Button
                onClick={() => {
                  const nextName = (name || hh.name).trim();
                  const nextCurrency = (currency || hh.currency).trim().toUpperCase();
                  const next: typeof hhErrors = {};
                  if (!nextName) next.name = t("errors.field_required");
                  if (!nextCurrency) next.currency = t("errors.field_required");
                  if (Object.keys(next).length > 0) {
                    setHhErrors(next);
                    return;
                  }
                  setHhErrors({});
                  updateHh.mutate({
                    name: nextName,
                    currency: nextCurrency,
                    defaultLocale: defaultLocale || hh.defaultLocale,
                  });
                }}
              >
                {t("common.save")}
              </Button>
            )}
          </CardBody>
        </Card>
      )}

      {isOwner && <AutoSnapshotCard householdId={household.householdId} />}

      {isOwner && <NotificationsCard householdId={household.householdId} />}

      {bankConfig?.featureEnabled && (
        <BanksCard householdId={household.householdId} isOwner={isOwner} locale={i18n.language} />
      )}

      <Card>
        <CardHeader>
          <div className="flex items-start justify-between gap-3">
            <div>
              <p className="font-medium">{t("settings.custom_categories_title")}</p>
              <p className="mt-1 text-sm text-gray-500 dark:text-gray-400">{t("settings.custom_categories_description")}</p>
            </div>
            {isOwner && (
              <Button
                variant="secondary"
                onClick={() => { setCustomEditing(null); setCustomDialogOpen(true); }}
              >
                {t("settings.add_custom_category")}
              </Button>
            )}
          </div>
        </CardHeader>
        <CardBody>
          {customCategories.length === 0 ? (
            <p className="text-sm text-gray-500 dark:text-gray-400">{t("common.empty")}</p>
          ) : (
            <ul className="space-y-2">
              {customCategories.map((c) => {
                const isDeleting = customDeleteTarget === c.code;
                return (
                  <li key={c.code} className="rounded-md border border-border p-3 dark:border-gray-700">
                    <div className="flex flex-wrap items-center justify-between gap-2">
                      <div className="min-w-0 break-words">
                        <span className="mr-1.5" aria-hidden>{categoryIcon(c.code)}</span>
                        <span className="font-medium">{c.name}</span>
                        <span className="ml-2 text-xs text-gray-500 dark:text-gray-400">
                          {c.kind === "expense" ? t(`category_group.${c.group}`) : t("common.income")}
                        </span>
                        {c.essential && (
                          <span className="ml-2 rounded bg-emerald-100 px-1.5 py-0.5 text-xs text-emerald-800 dark:bg-emerald-900/50 dark:text-emerald-200">
                            {t("settings.custom_category_essential")}
                          </span>
                        )}
                      </div>
                      {isOwner && (
                        <div className="inline-flex gap-1">
                          <Button
                            variant="ghost"
                            className="px-2"
                            aria-label={t("common.edit")}
                            title={t("common.edit")}
                            onClick={() => { setCustomEditing(c); setCustomDialogOpen(true); }}
                          >
                            <span aria-hidden>✏️</span>
                          </Button>
                          <Button
                            variant="ghost"
                            className="px-2 text-red-600 dark:text-red-400"
                            aria-label={t("common.delete")}
                            title={t("common.delete")}
                            onClick={() => {
                              setCustomDeleteError(null);
                              setCustomDeleteConfirm("");
                              setCustomDeleteTarget(c.code);
                            }}
                          >
                            <span aria-hidden>🗑️</span>
                          </Button>
                        </div>
                      )}
                    </div>
                    {isDeleting && (
                      <div className="mt-3 space-y-3 rounded-md border border-red-300 bg-red-50 p-3 dark:border-red-800 dark:bg-red-950/30">
                        <p className="text-sm text-red-800 dark:text-red-300">
                          {t("settings.delete_custom_category_warning")}
                        </p>
                        <div>
                          <Label>{t("settings.wipe_confirm_prompt")}</Label>
                          <Input
                            value={customDeleteConfirm}
                            onChange={(e) => { setCustomDeleteConfirm(e.target.value); if (customDeleteError) setCustomDeleteError(null); }}
                            placeholder="delete"
                            autoFocus
                          />
                        </div>
                        {customDeleteError && <FieldError message={customDeleteError} />}
                        <div className="flex gap-2">
                          <Button
                            variant="danger"
                            disabled={customDeleteConfirm !== "delete" || deleteCustom.isPending}
                            onClick={async () => {
                              setCustomDeleteError(null);
                              try {
                                await deleteCustom.mutateAsync(c.code);
                                setCustomDeleteTarget(null);
                                setCustomDeleteConfirm("");
                              } catch (err) {
                                const api = asApiError(err);
                                setCustomDeleteError(t(`errors.${api.code}`, api.message));
                              }
                            }}
                          >
                            {t("common.delete")}
                          </Button>
                          <Button
                            variant="secondary"
                            onClick={() => { setCustomDeleteTarget(null); setCustomDeleteConfirm(""); setCustomDeleteError(null); }}
                          >
                            {t("common.cancel")}
                          </Button>
                        </div>
                      </div>
                    )}
                  </li>
                );
              })}
            </ul>
          )}
        </CardBody>
      </Card>

      <Card>
        <CardHeader>
          <p className="font-medium">{t("settings.members_list_title")}</p>
          <p className="mt-1 text-sm text-gray-500 dark:text-gray-400">{t("settings.members_list_description")}</p>
        </CardHeader>
        <CardBody>
          {members.length === 0 ? (
            <p className="text-sm text-gray-500 dark:text-gray-400">{t("common.empty")}</p>
          ) : (
            <>
              <ul className="space-y-2 md:hidden">
                {members.map((m) => (
                  <li key={m.userId} className="rounded-md border border-border p-3 dark:border-gray-700">
                    <p className="break-words font-medium">
                      {m.email}
                      {user?.id === m.userId && (
                        <span className="ml-2 rounded bg-gray-100 px-1.5 py-0.5 text-xs text-gray-600 dark:bg-gray-800 dark:text-gray-300">
                          {t("settings.you")}
                        </span>
                      )}
                    </p>
                    <p className="mt-0.5 text-xs text-gray-500 dark:text-gray-400">
                      {t(`settings.${m.role}`)} · {new Date(m.joinedAt).toLocaleDateString()}
                    </p>
                  </li>
                ))}
              </ul>
              <table className="hidden w-full text-sm md:table">
                <thead className="text-left text-gray-500 dark:text-gray-400">
                  <tr>
                    <th className="py-2">{t("auth.email")}</th>
                    <th>{t("settings.invitation_role")}</th>
                    <th>{t("settings.joined_at")}</th>
                  </tr>
                </thead>
                <tbody>
                  {members.map((m) => (
                    <tr key={m.userId} className="border-t border-border">
                      <td className="py-2">
                        {m.email}
                        {user?.id === m.userId && (
                          <span className="ml-2 rounded bg-gray-100 px-1.5 py-0.5 text-xs text-gray-600 dark:bg-gray-800 dark:text-gray-300">
                            {t("settings.you")}
                          </span>
                        )}
                      </td>
                      <td>{t(`settings.${m.role}`)}</td>
                      <td>{new Date(m.joinedAt).toLocaleDateString()}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </>
          )}
        </CardBody>
      </Card>

      {isOwner && (
        <Card>
          <CardHeader>
            <p className="font-medium">{t("settings.invitations")}</p>
            <p className="mt-1 text-sm text-gray-500 dark:text-gray-400">{t("settings.invitations_description")}</p>
          </CardHeader>
          <CardBody className="space-y-3">
            <div className="grid grid-cols-1 gap-3 md:grid-cols-3">
              <div>
                <Label>{t("settings.invitation_email_optional")}</Label>
                <Input type="email" value={inviteEmail} onChange={(e) => setInviteEmail(e.target.value)} />
              </div>
              <div>
                <Label>{t("settings.invitation_role")}</Label>
                <Select value={inviteRole} onChange={(e) => setInviteRole(e.target.value as "owner" | "member")}>
                  <option value="member">{t("settings.member")}</option>
                  <option value="owner">{t("settings.owner")}</option>
                </Select>
              </div>
              <div className="flex items-end">
                <Button
                  onClick={async () => {
                    const result = await issue.mutateAsync({ email: inviteEmail || undefined, role: inviteRole });
                    setIssuedToken(result.token);
                    setInviteEmail("");
                  }}
                >
                  {t("settings.issue_token")}
                </Button>
              </div>
            </div>
            {issuedToken && (
              <div className="rounded border border-sky-300 bg-sky-50 p-3 text-sm">
                <p className="font-medium">{t("settings.issued_token")}</p>
                <code className="block break-all rounded bg-white px-2 py-1">{issuedToken}</code>
                <p className="mt-2 text-xs text-gray-600 dark:text-gray-300">{window.location.origin}/register?invite={issuedToken}</p>
              </div>
            )}
            {invitations.length === 0 ? (
              <p className="text-sm text-gray-500 dark:text-gray-400">{t("common.empty")}</p>
            ) : (
              <>
                <ul className="space-y-2 md:hidden">
                  {invitations.map((i) => (
                    <li key={i.id} className="rounded-md border border-border p-3 dark:border-gray-700">
                      <div className="flex items-start justify-between gap-2">
                        <div className="min-w-0 flex-1 break-words">
                          <p className="font-medium">{i.email ?? "—"}</p>
                          <p className="mt-0.5 text-xs text-gray-500 dark:text-gray-400">
                            {t(`settings.${i.role}`)} · {new Date(i.expiresAt).toLocaleDateString()}
                          </p>
                        </div>
                        <Button variant="ghost" onClick={() => revoke.mutate(i.id)}>{t("settings.revoke")}</Button>
                      </div>
                    </li>
                  ))}
                </ul>
                <table className="hidden w-full text-sm md:table">
                  <thead className="text-left text-gray-500 dark:text-gray-400">
                    <tr>
                      <th className="py-2">{t("settings.invitation_role")}</th>
                      <th>{t("auth.email")}</th>
                      <th>{t("common.date")}</th>
                      <th></th>
                    </tr>
                  </thead>
                  <tbody>
                    {invitations.map((i) => (
                      <tr key={i.id} className="border-t border-border">
                        <td className="py-2">{t(`settings.${i.role}`)}</td>
                        <td>{i.email ?? "—"}</td>
                        <td>{new Date(i.expiresAt).toLocaleDateString()}</td>
                        <td className="text-right">
                          <Button variant="ghost" onClick={() => revoke.mutate(i.id)}>{t("settings.revoke")}</Button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </>
            )}
          </CardBody>
        </Card>
      )}

      {isOwner && (
        <Card className="border-red-300 dark:border-red-800">
          <CardHeader>
            <p className="font-medium text-red-700 dark:text-red-400">{t("settings.danger_zone")}</p>
            <p className="mt-1 text-sm text-gray-500 dark:text-gray-400">{t("settings.wipe_description")}</p>
          </CardHeader>
          <CardBody className="space-y-3">
            {!wipeOpen ? (
              <Button
                variant="danger"
                onClick={() => {
                  setWipeOpen(true);
                  setWipeConfirm("");
                  setWipeMsg(null);
                }}
              >
                {t("settings.wipe_action")}
              </Button>
            ) : (
              <div className="space-y-3 rounded-md border border-red-300 bg-red-50 p-3 dark:border-red-800 dark:bg-red-950/30">
                <p className="text-sm text-red-800 dark:text-red-300">{t("settings.wipe_warning")}</p>
                <div>
                  <Label>{t("settings.wipe_confirm_prompt")}</Label>
                  <Input
                    value={wipeConfirm}
                    onChange={(e) => { setWipeConfirm(e.target.value); if (wipeMsg) setWipeMsg(null); }}
                    placeholder="delete"
                    autoFocus
                  />
                </div>
                {wipeMsg && <FieldError message={wipeMsg} />}
                <div className="flex gap-2">
                  <Button
                    variant="danger"
                    disabled={wipeConfirm !== "delete" || wipe.isPending}
                    onClick={async () => {
                      try {
                        await wipe.mutateAsync(wipeConfirm);
                        setWipeOpen(false);
                        setWipeConfirm("");
                      } catch (err) {
                        const api = asApiError(err);
                        setWipeMsg(t(`errors.${api.code}`, api.message));
                      }
                    }}
                  >
                    {t("settings.wipe_confirm_button")}
                  </Button>
                  <Button
                    variant="secondary"
                    onClick={() => { setWipeOpen(false); setWipeConfirm(""); setWipeMsg(null); }}
                  >
                    {t("common.cancel")}
                  </Button>
                </div>
              </div>
            )}
          </CardBody>
        </Card>
      )}

      <CreateHouseholdDialog
        open={createOpen}
        onClose={() => setCreateOpen(false)}
        onCreated={async (householdId) => {
          await refresh();
          setActiveHouseholdId(householdId);
          setCreateOpen(false);
        }}
      />

      <CustomCategoryDialog
        open={customDialogOpen}
        householdId={household.householdId}
        editing={customEditing}
        onClose={() => setCustomDialogOpen(false)}
        onSaved={() => { setCustomDialogOpen(false); setCustomEditing(null); }}
      />
    </div>
  );
}
