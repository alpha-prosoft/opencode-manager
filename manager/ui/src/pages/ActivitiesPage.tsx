import {
  Accordion,
  AccordionDetails,
  AccordionSummary,
  Box,
  Chip,
  Paper,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  Typography,
} from "@mui/material";
import ExpandMoreIcon from "@mui/icons-material/ExpandMore";
import { api, type JsonSchema } from "../api";
import { activityMeta } from "../components/Chips";
import { CodeBlock, IconTile, Mono } from "../components/Data";
import { PageHeader, PageStack } from "../components/Page";
import { useLoad } from "../hooks";

function type(field: JsonSchema): string {
  if (field.enum) return field.enum.join(" | ");
  if (field.$ref) return field.$ref.split("/").pop() ?? "object";
  const options = field.anyOf?.map((o) => (o.$ref ? o.$ref.split("/").pop() : o.type)).filter((t) => t !== "null");
  return options?.join(" | ") ?? field.type ?? "object";
}

function Fields({ title, description, schema }: { title: string; description: string; schema: JsonSchema }) {
  const fields = Object.entries(schema.properties ?? {});
  if (fields.length === 0) return null;
  const required = new Set(schema.required ?? []);
  return (
    <Stack spacing={1}>
      <Box>
        <Typography variant="subtitle2">{title}</Typography>
        <Typography variant="body2" color="text.secondary">
          {description}
        </Typography>
      </Box>
      <Paper sx={{ overflowX: "auto" }}>
        <Table>
          <TableHead>
            <TableRow>
              <TableCell>Field</TableCell>
              <TableCell>Type</TableCell>
              <TableCell>What it does</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {fields.map(([name, field]) => (
              <TableRow key={name}>
                <TableCell>
                  <Mono>{name}</Mono>
                  {required.has(name) && <Chip label="required" variant="outlined" sx={{ ml: 1 }} />}
                </TableCell>
                <TableCell>
                  <Mono>{type(field)}</Mono>
                </TableCell>
                <TableCell>
                  {field.description}
                  {field.default !== undefined && field.default !== null && (
                    <Typography variant="body2" color="text.secondary">
                      Default: <Mono>{JSON.stringify(field.default)}</Mono>
                    </Typography>
                  )}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </Paper>
    </Stack>
  );
}

function defaults(schema: JsonSchema): Record<string, unknown> {
  return Object.fromEntries(
    Object.entries(schema.properties ?? {})
      .filter(([, field]) => field.default !== undefined && field.default !== null)
      .map(([name, field]) => [name, field.default]),
  );
}

export default function ActivitiesPage() {
  const catalog = useLoad(() => api.marketplace(), []);
  return (
    <PageStack>
      <PageHeader
        title="Activities"
        description="The kinds of work an agent can do on this platform. Teams build workflows from them: a workflow file picks one activity, an agent and a harness, and fixes the activity's options. People fill in the inputs each time they run it."
      />
      <Box>
        {(catalog.data ?? []).map((activity) => {
          return (
            <Accordion key={activity.id}>
              <AccordionSummary expandIcon={<ExpandMoreIcon />}>
                <Stack direction="row" spacing={2} sx={{ alignItems: "center", minWidth: 0 }}>
                  <IconTile icon={activityMeta(activity.id).icon} />
                  <Box sx={{ minWidth: 0 }}>
                    <Typography variant="h3">{activity.name}</Typography>
                    <Typography variant="body2" color="text.secondary">
                      {activity.description}
                    </Typography>
                  </Box>
                </Stack>
              </AccordionSummary>
              <AccordionDetails>
                <Stack spacing={3}>
                  <Typography variant="body2">
                    {activity.interactive
                      ? "Interactive: the run opens a session that a person works in."
                      : "Automated: the agent works alone and hands back a structured result, which the platform checks before showing it."}
                  </Typography>
                  <Fields title="Options" description="Set once, in the team's workflow file." schema={activity.options_schema} />
                  <Fields title="Inputs" description="Filled in by whoever runs the workflow." schema={activity.inputs_schema} />
                  <Box>
                    <Typography variant="subtitle2" gutterBottom>
                      Example workflow file
                    </Typography>
                    <CodeBlock>
                      {JSON.stringify(
                        {
                          name: activity.name,
                          activity: activity.id,
                          agent: "my-agent",
                          harness: "my-harness",
                          options: defaults(activity.options_schema),
                        },
                        null,
                        2,
                      )}
                    </CodeBlock>
                  </Box>
                </Stack>
              </AccordionDetails>
            </Accordion>
          );
        })}
      </Box>
    </PageStack>
  );
}
