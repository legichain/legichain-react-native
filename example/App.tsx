/**
 * Minimal demo of @legichain/sdk on React Native.
 */

import React from "react";
import { Button, SafeAreaView, ScrollView, Text } from "react-native";

import {
  KycFlowController,
  LegichainClient,
  type KycStatus,
} from "@legichain/sdk";

const API_KEY = "key_xxx.secret_yyy"; // ← paste yours

const client = new LegichainClient({ apiKey: API_KEY });

export default function App(): JSX.Element {
  const [log, setLog] = React.useState("ready");
  const flowRef = React.useRef<KycFlowController | null>(null);

  React.useEffect(() => () => flowRef.current?.dispose(), []);

  async function runScreening(): Promise<void> {
    setLog("screening…");
    const r = await client.screening.person({
      name: "Vladimir Putin",
      country: "RU",
      top_n: 5,
    });
    setLog(
      `recommendation=${r.summary.recommendation} ` +
        `hits=${r.hits.length} ` +
        `top_conf=${r.summary.top_match_confidence}`,
    );
  }

  async function startKyc(): Promise<void> {
    setLog("creating application…");
    const flow = await client.kyc.startFlow({
      subject_external_id: `demo-${Date.now()}`,
      document_type_allowed: ["passport"],
      claimed_full_name: "ERIKA MUSTERMANN",
      claimed_nationality: "DEU",
    });
    flowRef.current = flow;
    flow.on("state", (s: KycStatus) => {
      setLog(
        `state=${s.state} step=${s.current_step} ` +
          `attempt=${s.current_attempt}/${s.max_attempts} ` +
          `retry=${s.retry_available}`,
      );
    });
  }

  async function fakeUploadDoc(): Promise<void> {
    const flow = flowRef.current;
    if (!flow) return;
    // Replace with real JPEG bytes from a camera plugin.
    const fakeJpeg = new Uint8Array(2048);
    await flow.uploadDocument({
      side: "single",
      imageBytes: fakeJpeg,
      documentType: "passport",
    });
  }

  return (
    <SafeAreaView style={{ flex: 1, padding: 16 }}>
      <Button title="Run AML screening" onPress={runScreening} />
      <Button title="Start KYC application" onPress={startKyc} />
      <Button title="Upload (fake) document" onPress={fakeUploadDoc} />
      <ScrollView style={{ marginTop: 16 }}>
        <Text selectable>{log}</Text>
      </ScrollView>
    </SafeAreaView>
  );
}
