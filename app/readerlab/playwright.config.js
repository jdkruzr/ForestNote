import { defineConfig } from '@playwright/test';
export default defineConfig({
  testDir: './tests', timeout: 60000, workers: 1,
  use: { headless: true, viewport: { width: 600, height: 800 }, launchOptions: { executablePath: process.env.CHROME_PATH || '/usr/bin/google-chrome', args: ['--no-sandbox'] } },
  webServer: { command: 'npm run serve', url: 'http://127.0.0.1:4173/readerlab/index.html', reuseExistingServer: false },
});
