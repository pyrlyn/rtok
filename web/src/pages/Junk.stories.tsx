import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, userEvent, within } from "storybook/test";
import { connectSample, sampleSnapshot } from "../api/sample";
import { Hosts } from "./Hosts";
import { serve, withData } from "./storyData";

export default { title: "Pages/Junk" } satisfies Meta;

const story = (snapshot = serve(sampleSnapshot), theme?: "light"): StoryObj => ({
    render: () => <Hosts />,
    decorators: [withData(snapshot)],
    ...(theme && { globals: { theme } }),
});

export const JunkCard = story();
export const JunkCardLight = story(serve(sampleSnapshot), "light");
export const JunkMeasuring = story(serve({ ...sampleSnapshot, junk: null }));

// Plan, read the dry run and confirm against the mocked server (T330.7).
export const ClearSafeJunkFlow: StoryObj = {
    ...story(connectSample),
    play: async ({ canvasElement }) => {
        const junk = within(await within(canvasElement).findByRole("region", { name: "junk" }));
        await userEvent.click(await junk.findByRole("button", { name: "Clear safe junk" }));
        const plan = within(await junk.findByRole("region", { name: "junk plan" }));
        await expect(plan.getByText(/Dry run: .* nothing changed/)).toBeVisible();

        await userEvent.click(junk.getByRole("button", { name: "Clear 2 items" }));
        await expect(junk.getByText(/Delete 2 items/)).toBeVisible();
        await userEvent.click(junk.getByRole("button", { name: "Confirm" }));
        await expect(await junk.findByRole("status")).toHaveTextContent(
            "Freed 313.0 MB of 313.0 MB planned",
        );
    },
};
