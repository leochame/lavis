import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import './index.css';
import './tailwind.css';
import App from './App.tsx';
import { PlatformProvider } from './platforms/PlatformProvider';

const root = createRoot(document.getElementById('root')!);
root.render(
  <StrictMode>
    <PlatformProvider>
      <App />
    </PlatformProvider>
  </StrictMode>,
);
