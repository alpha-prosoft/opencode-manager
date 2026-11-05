import { Box, Card, CardContent, Chip, Stack, Table, TableBody, TableCell, TableHead, TableRow, Typography } from "@mui/material";
import WidgetsIcon from "@mui/icons-material/Widgets";
import { api, type JsonSchema } from "../api";
import { ACTIVITY_ICONS } from "../components/Chips";
import { useLoad } from "../hooks";

function type(field: JsonSchema): string {
  if (field.enum) return field.enum.join(" | ");
  if (field.$ref) return field.$ref.split("/").pop() ?? "object";
  const options = field.anyOf?.map((o) => (o.$ref ? o.$ref.split("/").pop() : o.type)).filter((t) => t !== "null");
  return options?.join(" | ") ?? field.type ?? "object";
}

function Fields({ title, schema }: { title: string; schema: JsonSchema }) {
  const fields = Object.entries(schema.properties ?? {});
  if (fields.length === 0) return null;
  const required = new Set(schema.required ?? []);
  return (
    <Box>
      <Typography variant="subtitle2" sx={{ mt: 2 }}>
        {title}
      </Typography>
      <Table size="small">
        <TableHead>
          <TableRow>
            <TableCell>Field</TableCell>
            <TableCell>Type</TableCell>
            <TableCell>Description</TableCell>
          </TableRow>
        </TableHead>
        <TableBody>
          {fields.map(([name, field]) => (
            <TableRow key={name}>
              <TableCell sx={{ fontFamily: "monospace" }}>
                {name}
                {required.has(name) ? " *" : ""}
              </TableCell>
              <TableCell sx={{ fontFamily: "monospace", color: "text.secondary" }}>{type(field)}</TableCell>
              <TableCell>
                {field.description}
                {field.default !== undefined && field.default !== null && (
                  <Typography component="span" variant="caption" color="text.secondary">
                    {" "}
                    (default: {JSON.stringify(field.default)})
                  </Typography>
                )}
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </Box>
  );
}

export default function MarketplacePage() {
  const catalog = useLoad(() => api.marketplace(), []);
  return (
    <Stack spacing={3}>
      <Box>
        <Typography variant="h4">Marketplace</Typography>
        <Typography color="text.secondary">
          Activities your team can wire into workflows. A workflow picks an activity, an agent and a harness, sets the
          activity's <em>options</em> once, and supplies <em>inputs</em> on every run.
        </Typography>
      </Box>
      {(catalog.data ?? []).map((activity) => {
        const Icon = ACTIVITY_ICONS[activity.id] ?? WidgetsIcon;
        return (
          <Card key={activity.id} variant="outlined">
            <CardContent>
              <Stack direction="row" spacing={1.5} sx={{ alignItems: "center", mb: 1 }}>
                <Icon color="primary" fontSize="large" />
                <Box sx={{ flexGrow: 1 }}>
                  <Typography variant="h6">{activity.name}</Typography>
                  <Typography variant="caption" color="text.secondary" sx={{ fontFamily: "monospace" }}>
                    activity: "{activity.id}"
                  </Typography>
                </Box>
                <Chip label={activity.category} size="small" />
                {activity.interactive ? (
                  <Chip label="interactive" size="small" color="primary" variant="outlined" />
                ) : (
                  <Chip label={`result: ${activity.result_tool}`} size="small" variant="outlined" />
                )}
              </Stack>
              <Typography>{activity.description}</Typography>
              <Fields title="Options (set in the workflow file)" schema={activity.options_schema} />
              <Fields title="Inputs (given per run)" schema={activity.inputs_schema} />
            </CardContent>
          </Card>
        );
      })}
    </Stack>
  );
}
