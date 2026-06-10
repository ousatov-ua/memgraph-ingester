import { defineConfig } from 'vitepress'

const rawBase = process.env.VITEPRESS_BASE
const base = rawBase
  ? rawBase.startsWith('/')
    ? rawBase.endsWith('/')
      ? rawBase
      : `${rawBase}/`
    : `/${rawBase}/`
  : '/'

export default defineConfig({
  base,
  title: 'Memgraph Ingester',
  description: 'Structure-aware RAG for code and durable project knowledge',
  cleanUrls: true,
  head: [
    ['meta', { name: 'theme-color', content: '#2f7d68' }],
    ['meta', { property: 'og:type', content: 'website' }],
    ['meta', { property: 'og:title', content: 'Memgraph Ingester' }],
    [
      'meta',
      {
        property: 'og:description',
        content: 'Index source files into Memgraph so AI agents can query code as a graph.',
      },
    ],
    [
      'meta',
      {
        property: 'og:image',
        content:
          'https://raw.githubusercontent.com/ousatov-ua/memgraph-ingester/main/image/memgraph-ingester-social-preview-original-content.png',
      },
    ],
  ],
  themeConfig: {
    logo: 'https://raw.githubusercontent.com/ousatov-ua/memgraph-ingester/main/image/memgraph-ingester-readme-banner-640x320.png',
    nav: [
      { text: 'Guide', link: '/guides/getting-started' },
      { text: 'Agent Setup', link: '/guides/agent-setup' },
      { text: 'CLI Reference', link: '/reference/cli' },
      { text: 'Benchmarks', link: '/benchmarks/' },
    ],
    sidebar: {
      '/guides/': [
        {
          text: 'Guides',
          items: [
            { text: 'Getting Started', link: '/guides/getting-started' },
            { text: 'Agent Setup', link: '/guides/agent-setup' },
          ],
        },
      ],
      '/reference/': [
        {
          text: 'Reference',
          items: [{ text: 'CLI Reference', link: '/reference/cli' }],
        },
      ],
      '/benchmarks/': [
        {
          text: 'Benchmarks',
          items: [
            { text: 'Overview', link: '/benchmarks/' },
            { text: 'Task Definitions', link: '/benchmarks/tasks' },
            { text: 'Full Results', link: '/benchmarks/res' },
          ],
        },
      ],
    },
    outline: [2, 3],
    search: {
      provider: 'local',
    },
    socialLinks: [
      { icon: 'github', link: 'https://github.com/ousatov-ua/memgraph-ingester' },
    ],
    footer: {
      message: 'Released under the MIT License.',
      copyright: 'Copyright © Oleksii Usatov',
    },
  },
})
