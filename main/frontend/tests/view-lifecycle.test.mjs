import assert from "node:assert/strict";
import test from "node:test";

import { MainView } from "../src/components/main-view.js";
import { defineView } from "../src/core/view-contract.js";
import { registerView } from "../src/core/view-registry.js";

/**
 * 最小 DOM 元素替身，只覆盖 MainView 实际使用的接口。
 */
class FakeElement {
    children = [];
    innerHTML = "";
    textContent = "";
    className = "";

    append(...children) {
        this.children.push(...children);
    }

    replaceChildren(...children) {
        this.children = children;
    }

    setAttribute() {}
    scrollTo() {}
    focus() {}
}

/**
 * 最小 document 替身。
 */
const fakeDocument = {
    createElement: () => new FakeElement()
};

test("view switching unmounts old resources before mounting the next view", async () => {
    const events = [];
    let firstSignal;

    registerView("__lifecycle_a__", defineView({
        name: "__lifecycle_a__",
        render: () => {
            events.push("render-a");
            return "<section>A</section>";
        },
        mount: (_container, context) => {
            events.push("mount-a");
            firstSignal = context.signal;
            context.registerCleanup(() => events.push("cleanup-a"));
        },
        unmount: () => events.push("unmount-a")
    }));

    registerView("__lifecycle_b__", defineView({
        name: "__lifecycle_b__",
        render: () => {
            events.push("render-b");
            return "<section>B</section>";
        },
        mount: () => events.push("mount-b"),
        unmount: () => events.push("unmount-b")
    }));

    let motionCount = 0;
    const mainView = new MainView({
        stage: new FakeElement(),
        breadcrumb: new FakeElement(),
        motionEngine: { play: () => motionCount++ },
        documentRef: fakeDocument
    });

    await mainView.show({ label: "A", view: "__lifecycle_a__" });
    assert.deepEqual(events, ["render-a", "mount-a"]);
    assert.equal(firstSignal.aborted, false);

    await mainView.show({ label: "B", view: "__lifecycle_b__" });
    assert.deepEqual(events, [
        "render-a",
        "mount-a",
        "unmount-a",
        "cleanup-a",
        "render-b",
        "mount-b"
    ]);
    assert.equal(firstSignal.aborted, true);
    assert.equal(motionCount, 2);
});

test("destroy is idempotent and aborts the active view", async () => {
    let mountCount = 0;
    let unmountCount = 0;
    let activeSignal;

    registerView("__lifecycle_destroy__", defineView({
        name: "__lifecycle_destroy__",
        render: () => "<section>Destroy</section>",
        mount: (_container, context) => {
            mountCount++;
            activeSignal = context.signal;
        },
        unmount: () => {
            unmountCount++;
        }
    }));

    const mainView = new MainView({
        stage: new FakeElement(),
        breadcrumb: new FakeElement(),
        motionEngine: { play() {} },
        documentRef: fakeDocument
    });

    await mainView.show({ label: "Destroy", view: "__lifecycle_destroy__" });
    await mainView.destroy();
    await mainView.destroy();

    assert.equal(mountCount, 1);
    assert.equal(unmountCount, 1);
    assert.equal(activeSignal.aborted, true);
});
