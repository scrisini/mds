// semantic-release configuration (README "Commit messages (semantic-release)"). A module rather
// than JSON so the release notes can carry each change's details (detailsOf, below).

// Trailers are for git and GitHub, not readers: credit and references stay in the commit.
const TRAILER = /^(Co-Authored-By|Signed-off-by|Reviewed-by|Refs):/i;

/**
 * The body of each change in the release, without trailers. A release is one squash commit whose
 * header becomes a single line under "Features" or "Bug Fixes"; what it changes, and how to
 * upgrade, is in its body, which the conventional-changelog templates leave out.
 */
function detailsOf(context) {
  const details = [];
  for (const group of context.commitGroups) {
    for (const commit of group.commits) {
      const body = (commit.body ?? '')
        .split('\n')
        .filter((line) => !TRAILER.test(line))
        .join('\n')
        .trim();
      if (body) {
        details.push(body);
      }
    }
  }
  return details;
}

export default {
  branches: ['main'],
  tagFormat: 'v${version}',
  plugins: [
    [
      '@semantic-release/commit-analyzer',
      {
        preset: 'conventionalcommits',
        releaseRules: [
          { breaking: true, release: 'major' },
          { type: 'feat', release: 'minor' },
          { type: 'fix', release: 'patch' },
          { type: 'perf', release: 'patch' },
          { type: 'refactor', release: 'patch' },
          { type: 'revert', release: 'patch' },
          { type: 'docs', release: false },
          { type: 'test', release: false },
          { type: 'build', release: false },
          { type: 'ci', release: false },
          { type: 'chore', release: false },
          { type: 'style', release: false },
        ],
        parserOpts: {
          noteKeywords: ['BREAKING CHANGE', 'BREAKING-CHANGE'],
        },
      },
    ],
    [
      '@semantic-release/release-notes-generator',
      {
        preset: 'conventionalcommits',
        presetConfig: {
          types: [
            { type: 'feat', section: 'Features' },
            { type: 'fix', section: 'Bug Fixes' },
            { type: 'perf', section: 'Performance' },
            { type: 'refactor', section: 'Refactoring' },
            { type: 'revert', section: 'Reverts' },
            { type: 'docs', hidden: true },
            { type: 'test', hidden: true },
            { type: 'build', hidden: true },
            { type: 'ci', hidden: true },
            { type: 'chore', hidden: true },
            { type: 'style', hidden: true },
          ],
        },
        // After the one-line summary per change (and BREAKING CHANGES): a Details section with
        // each change's body. The preset's own footer is empty.
        writerOpts: {
          finalizeContext: (context) => ({ ...context, details: detailsOf(context) }),
          footerPartial: '{{#if details}}\n\n### Details\n\n{{#each details}}{{this}}\n\n{{/each}}{{/if}}',
        },
      },
    ],
    '@semantic-release/github',
  ],
};
