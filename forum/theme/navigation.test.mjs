import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { join, relative } from 'node:path';
import test from 'node:test';

const root = fileURLToPath(new URL('../../', import.meta.url));
const site = 'https://bokfri.viblo.se/';
const forum = 'https://forum.bokfri.viblo.se/';
const expected = [
    { text: 'Hem', href: site },
    { text: 'Ladda ner', href: `${site}download/` },
    { text: 'Hjälp', href: `${site}help/` },
    { text: 'Forum', href: forum },
];
const read = (path) => readFileSync(join(root, path), 'utf8');

function pages(directory) {
    return readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
        const path = join(directory, entry.name);
        return entry.isDirectory() ? pages(path) : entry.name.endsWith('.html') ? [path] : [];
    });
}

function navigation(html, className, base) {
    const matches = [...html.matchAll(new RegExp(`<nav class="${className}"[^>]*>[\\s\\S]*?<\\/nav>`, 'g'))];
    assert.equal(matches.length, 1, 'exactly one shared navigation');
    const nav = matches[0][0];
    assert.match(nav, /aria-label="[^"]+"/, 'navigation has an accessible name');
    const links = [...nav.matchAll(/<a\b([^>]*)>([^<]+)<\/a>/g)].map(([, attributes, text]) => {
        assert.doesNotMatch(attributes, /target=/, 'navigation stays in the same tab');
        const href = attributes.match(/href="([^"]+)"/)?.[1];
        assert.ok(href, 'navigation link has a destination');
        return { text, href: new URL(href, base).href };
    });
    return { nav, links };
}

test('all static website pages share the same four navigation destinations', async (t) => {
    const files = pages(join(root, 'website'));
    assert.ok(files.length > 200, 'scan the help reference as well as landing pages');
    for (const path of files) {
        await t.test(relative(root, path), () => {
            const html = readFileSync(path, 'utf8');
            assert.deepEqual(navigation(html, 'header-nav', site).links, expected);
            assert.match(html, /<img src="\/assets\/logo.svg" alt="Bokfri"/);
        });
    }
});

test('website reserves scrollbar space so short pages keep the same header width', () => {
    const css = read('website/style.css');
    assert.match(css, /html\s*\{[^}]*overflow-y:\s*scroll\s*;/,
        'reserve the vertical scrollbar on the root, including the short download page');
    for (const path of ['website/index.html', 'website/download/index.html', 'website/help/index.html']) {
        const href = read(path).match(/<link rel="stylesheet" href="([^"]+)">/)?.[1];
        assert.ok(href, `${path} has a stylesheet`);
        const pageUrl = new URL(path.replace(/^website\//, ''), site);
        assert.equal(new URL(href, pageUrl).href, `${site}style.css`,
            `${path} uses the shared width rules`);
    }
});

test('forum header matches website destinations and marks the forum section current', () => {
    const html = read('forum/theme/header.html');
    const { nav, links } = navigation(html, 'bokfri-header-nav', forum);
    assert.deepEqual(links, expected);
    assert.match(nav, /href="https:\/\/forum\.bokfri\.viblo\.se\/"[^>]*aria-current="true"/);
    assert.match(html, /src="https:\/\/bokfri\.viblo\.se\/assets\/logo.svg"/);
    assert.doesNotMatch(nav, /href="\//, 'forum links cannot resolve against the wrong host');
});

test('help/contact text includes forum, email and GitHub without requiring product registration', () => {
    for (const path of ['website/index.html', 'website/help/support.html']) {
        const body = read(path).split('</header>')[1];
        assert.match(body, /href="https:\/\/forum\.bokfri\.viblo\.se\/"/);
        assert.match(body, /href="mailto:vb@viblo.se"/);
        assert.match(body, /href="https:\/\/github\.com\/vibloteket\/bokfri\/issues"/);
        assert.match(body, /Publicera inte bokföringsdata/);
    }
    assert.match(read('website/help/index.html').split('</header>')[1], /href="https:\/\/forum\.bokfri\.viblo\.se\/"/);
    assert.doesNotMatch(read('website/help/support.html'), /måste du registrera produkten/);
    assert.match(read('website/help/support.html'), /Bokfri-programmet kräver inget konto/);
    const readme = read('README.md');
    assert.match(readme, /\[Forum\]\(https:\/\/forum\.bokfri\.viblo\.se\/\)/);
    assert.match(readme, /\[Bokfris forum\]\(https:\/\/forum\.bokfri\.viblo\.se\/\)/);
    assert.match(readme, /forumet eller i en öppen felrapport/);
});
