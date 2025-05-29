import React, { useState } from 'react';
import { useAppSelector } from 'app/config/store';
import { Storage } from 'react-jhipster';

const MetabaseQuery = () => {
  const [question, setQuestion] = useState('');
  const [embedUrl, setEmbedUrl] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);

  const isAuthenticated = useAppSelector(state => state.authentication.isAuthenticated);
  const authToken = Storage.local.get('jhi-authenticationToken') || Storage.session.get('jhi-authenticationToken');

  async function handleSubmit(e) {
    e.preventDefault();
    if (!isAuthenticated || !authToken) {
      setError('Please login first');
      return;
    }

    setLoading(true);
    setError(null);
    setEmbedUrl(null);

    try {
      const response = await fetch('/api/query', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${authToken}`,
        },
        body: JSON.stringify({ question }),
      });

      if (!response.ok) {
        throw new Error(response.status === 401 ? 'Authentication required' : 'Failed to fetch visualization');
      }

      const data = await response.json();
      setEmbedUrl(data.embedUrl);
    } catch (err) {
      setError(err.message);
      console.error('Error:', err);
    } finally {
      setLoading(false);
    }
  }

  return (
    <div>
      <form onSubmit={handleSubmit} style={{ marginBottom: '1rem' }}>
        <input
          type="text"
          style={{ width: '70%' }}
          placeholder="Ask your analytics question..."
          value={question}
          onChange={e => setQuestion(e.target.value)}
          style={{ width: '70%', padding: '0.5rem' }}
          required
        />
        <button type="submit" disabled={loading} style={{ marginLeft: '1rem', padding: '0.5rem 1rem' }}>
          {loading ? 'Loading...' : 'Ask'}
        </button>
      </form>

      {error && <div style={{ color: 'red' }}>{error}</div>}

      {embedUrl && <iframe src={embedUrl} frameBorder="0" width="100%" height="600" title="Metabase Visualization" allowTransparency />}
    </div>
  );
};

export default MetabaseQuery;
