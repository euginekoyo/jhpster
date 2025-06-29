import './home.scss';

import React from 'react';
import { Link } from 'react-router-dom';
import { Translate } from 'react-jhipster';
import { Alert, Col, Row } from 'reactstrap';

import { useAppSelector } from 'app/config/store';
import MetabaseQuery from './metabaseQuery';
import Metabase from 'app/modules/home/metabase';

export const Home = () => {
  const account = useAppSelector(state => state.authentication.account);

  return (
    <Row>
      {/* <Col md="3" className="pad">*/}
      {/*  <span className="hipster rounded" />*/}
      {/* </Col>*/}
      <Col md="9">
        {/* <p className="lead">*/}
        {/*  <Translate contentKey="home.subtitle">Showing Metabase embedded Visuals ,Just some public links</Translate>*/}
        {/* </p>*/}
        {account?.login ? (
          <>
            {/* <h1 className="display-4">*/}
            {/*  <Translate contentKey="home.title">Welcome,{account.login}</Translate>*/}
            {/* </h1>*/}
            <div>
              <Alert color="success">
                <Translate contentKey="home.logged.message" interpolate={{ username: account.login }}>
                  You are logged in as user {account.login}.
                </Translate>
              </Alert>
            </div>
            <div className="embed-responsive embed-responsive-16by9">
              <Metabase />
            </div>
          </>
        ) : (
          <div>
            <Alert color="warning">
              <Translate contentKey="global.messages.info.authenticated.prefix">If you want to </Translate>

              <Link to="/login" className="alert-link">
                <Translate contentKey="global.messages.info.authenticated.link"> sign in</Translate>
              </Link>
              <Translate contentKey="global.messages.info.authenticated.suffix">
                , you can try the default accounts:
                <br />- Administrator (login=&quot;admin&quot; and password=&quot;admin&quot;)
                <br />- User (login=&quot;user&quot; and password=&quot;user&quot;).
              </Translate>
            </Alert>

            <Alert color="warning">
              <Translate contentKey="global.messages.info.register.noaccount">You do not have an account yet?</Translate>&nbsp;
              <Link to="/account/register" className="alert-link">
                <Translate contentKey="global.messages.info.register.link">Register a new account</Translate>
              </Link>
            </Alert>
          </div>
        )}
        {/* <p>*/}
        {/*  <Translate contentKey="home.question">If you have any question on JHipster:</Translate>*/}
        {/* </p>*/}
      </Col>
    </Row>
  );
};

export default Home;
