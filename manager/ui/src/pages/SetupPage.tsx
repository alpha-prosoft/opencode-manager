import { useState } from "react";
import { useNavigate, useSearchParams } from "react-router";
import { Alert, Box, Button, Paper, Stack, Step, StepContent, StepLabel, Stepper, Typography } from "@mui/material";
import { api, type CredentialStatus } from "../api";
import { isSessionEntry, safeNext } from "../auth";
import CredentialForm from "../components/CredentialForm";
import { PageHeader, PageStack } from "../components/Page";
import { useLoad } from "../hooks";

function stepHint(credential: CredentialStatus): string {
  if (credential.required) return "Required for the tools that use it. Each person uses their own.";
  if (credential.state === "platform") return "Optional: a shared account is set up. Add yours to act as yourself.";
  return "Optional.";
}

export default function SetupPage() {
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const next = safeNext(params.get("next"));
  const data = useLoad(() => api.myCredentials(), []);
  const [step, setStep] = useState(0);
  const [saved, setSaved] = useState<string[]>([]);

  if (!data.data) return null;
  const pending = data.data.credentials.filter((c) => data.data!.pending.includes(c.id));
  const finish = async () => {
    await api.markCredentialsSeen(pending.map((c) => c.id));
    if (isSessionEntry(next)) window.location.assign(next);
    else navigate(next, { replace: true });
  };
  const advance = () => (step + 1 < pending.length ? setStep(step + 1) : finish());

  return (
    <PageStack>
      <PageHeader
        title="Connect your accounts"
        description="Some tools your teams use act on your behalf, so they need your own credentials. You can change these any time under Accounts."
      />
      {pending.length === 0 ? (
        <Alert severity="success" action={<Button onClick={() => navigate(next)}>Continue</Button>}>
          Everything is set up.
        </Alert>
      ) : (
        <Paper sx={{ p: 3, maxWidth: 720 }}>
          <Stepper activeStep={step} orientation="vertical">
            {pending.map((credential) => (
              <Step key={credential.id} completed={saved.includes(credential.id)}>
                <StepLabel optional={<Typography variant="caption">{stepHint(credential)}</Typography>}>{credential.name}</StepLabel>
                <StepContent>
                  <Stack spacing={2} sx={{ pt: 1 }}>
                    {credential.description && <Typography>{credential.description}</Typography>}
                    <CredentialForm
                      name={credential.name}
                      kind={credential.kind}
                      help={credential.help}
                      checks={Boolean(credential.test)}
                      save={(value) => api.setMyCredential(credential.id, value)}
                      onSaved={() => {
                        setSaved([...saved, credential.id]);
                        advance();
                      }}
                      extraActions={
                        <Button onClick={advance}>{credential.state === "platform" ? "Use the shared account" : "Skip for now"}</Button>
                      }
                    />
                  </Stack>
                </StepContent>
              </Step>
            ))}
          </Stepper>
          <Box sx={{ mt: 2 }}>
            <Button onClick={finish}>Do this later</Button>
          </Box>
        </Paper>
      )}
    </PageStack>
  );
}
